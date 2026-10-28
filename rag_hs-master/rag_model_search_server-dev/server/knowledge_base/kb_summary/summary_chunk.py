import asyncio
import sys

from langchain.chains import LLMChain, StuffDocumentsChain
from langchain.chains.combine_documents.map_reduce import MapReduceDocumentsChain, ReduceDocumentsChain
from langchain.docstore.document import Document
from langchain.prompts import PromptTemplate
from langchain.schema.language_model import BaseLanguageModel

from configs import logger
from server.knowledge_base.model.kb_document_model import DocumentWithVSId


class SummaryAdapter:
    _OVERLAP_SIZE: int
    token_max: int
    _separator: str = "\n\n"
    chain: MapReduceDocumentsChain

    def __init__(self, overlap_size: int, token_max: int,
                 chain: MapReduceDocumentsChain):
        self._OVERLAP_SIZE = overlap_size
        self.chain = chain
        self.token_max = token_max

    @classmethod
    def form_summary(cls,
                     llm: BaseLanguageModel,
                     reduce_llm: BaseLanguageModel,
                     overlap_size: int,
                     token_max: int = 4096):
        """
        获取实例
        :param reduce_llm: 用于合并摘要的llm
        :param llm: 用于生成摘要的llm
        :param overlap_size: 重叠部分大小
        :param token_max: 最大的chunk数量，每个chunk长度小于token_max长度，第一次生成摘要时，大于token_max长度的摘要会报错
        :return:
        """

        # This controls how each document will be formatted. Specifically,
        document_prompt = PromptTemplate(
            input_variables=["page_content"],
            template="{page_content}"
        )

        # The prompt here should take as an input variable the
        # `document_variable_name`
        prompt_template = (
            "根据文本执行任务。以下任务信息"
            "{task_briefing}"
            "文本内容如下: "
            "\r\n"
            "{context}"
        )
        prompt = PromptTemplate(
            template=prompt_template,
            input_variables=["task_briefing", "context"]
        )
        llm_chain = LLMChain(llm=llm, prompt=prompt)
        # We now define how to combine these summaries
        reduce_prompt = PromptTemplate.from_template(
            "Combine these summaries: {context}"
        )
        reduce_llm_chain = LLMChain(llm=reduce_llm, prompt=reduce_prompt)

        document_variable_name = "context"
        combine_documents_chain = StuffDocumentsChain(
            llm_chain=reduce_llm_chain,
            document_prompt=document_prompt,
            document_variable_name=document_variable_name
        )
        reduce_documents_chain = ReduceDocumentsChain(
            token_max=token_max,
            combine_documents_chain=combine_documents_chain,
        )
        chain = MapReduceDocumentsChain(
            llm_chain=llm_chain,
            document_variable_name=document_variable_name,
            reduce_documents_chain=reduce_documents_chain,
            # 返回中间步骤
            return_intermediate_steps=True
        )
        return cls(overlap_size=overlap_size,
                   chain=chain,
                   token_max=token_max)

    def summarize(self,
                  file_description: str,
                  docs: list[DocumentWithVSId] = None
                  ) -> list[Document]:
        if docs is None:
            docs = []

        if sys.version_info < (3, 10):
            loop = asyncio.get_event_loop()
        else:
            try:
                loop = asyncio.get_running_loop()
            except RuntimeError:
                loop = asyncio.new_event_loop()

            asyncio.set_event_loop(loop)
        # 同步调用协程代码
        return loop.run_until_complete(self.asummarize(file_description=file_description,
                                                       docs=docs))

    async def asummarize(self,
                         file_description: str,
                         docs: list[DocumentWithVSId] = None) -> list[Document]:
        if docs is None:
            docs = []

        logger.info("start summary")
        # TODO 暂不处理文档中涉及语义重复、上下文缺失、document was longer than the context length 的问题

        """
        这个过程分成两个部分：
        1. 对每个文档进行处理，得到每个文档的摘要
         map_results = self.llm_chain.apply(
            # FYI - this is parallelized and so it is fast.
            [{self.document_variable_name: d.page_content, **kwargs} for d in docs],
            callbacks=callbacks,
        )
        2. 对每个文档的摘要进行合并，得到最终的摘要，return_intermediate_steps=True，返回中间步骤
        result, extra_return_dict = self.reduce_documents_chain.combine_docs(
            result_docs, token_max=token_max, callbacks=callbacks, **kwargs
        )
        """
        summary_combine, summary_intermediate_steps = "", {}
        try:
            summary_combine, summary_intermediate_steps = self.chain.combine_docs(docs=docs,
                                                                                  task_briefing="描述不同方法之间的接近度和相似性，"
                                                                                                "以帮助读者理解它们之间的关系。")
        except ValueError as e:
            logger.error(f"summary chain ValueError: {e}（可能是单个文档超过 context length，已跳过摘要生成）")
            summary_combine = ""
            summary_intermediate_steps = {"error": str(e)}
        logger.debug(f"summary_combine: {summary_combine}")
        logger.debug(f"summary_intermediate_steps: {summary_intermediate_steps}")

        if not summary_combine:
            logger.warning("summary_combine 为空（docs 为空、LLM 返回空或超过 context length），跳过摘要生成")
            return []
        logger.info("end summary")
        doc_ids = ",".join([doc.id for doc in docs])
        _metadata = {
            "file_description": file_description,
            "summary_intermediate_steps": summary_intermediate_steps,
            "doc_ids": doc_ids
        }
        summary_combine_doc = Document(page_content=summary_combine, metadata=_metadata)

        return [summary_combine_doc]

    def _drop_overlap(self, docs: list[DocumentWithVSId]) -> list[str]:
        """
         # 将文档中page_content句子叠加的部分去掉
        :param docs:
        :param separator:
        :return:
        """
        merge_docs = []

        pre_doc = None
        for doc in docs:
            # 第一个文档直接添加
            if len(merge_docs) == 0:
                pre_doc = doc.page_content
                merge_docs.append(doc.page_content)
                continue

            # 列表中上一个结尾与下一个开头重叠的部分，删除下一个开头重叠的部分
            # 迭代递减pre_doc的长度，每次迭代删除前面的字符，
            # 查询重叠部分，直到pre_doc的长度小于 self._OVERLAP_SIZE // 2 - 2len(separator)
            for _ in range(len(pre_doc), self._OVERLAP_SIZE // 2 - 2 * len(self._separator), -1):
                # 每次迭代删除前面的字符
                pre_doc = pre_doc[1:]
                if doc.page_content[:len(pre_doc)] == pre_doc:
                    # 删除下一个开头重叠的部分
                    merge_docs.append(doc.page_content[len(pre_doc):])
                    break

            pre_doc = doc.page_content

        return merge_docs

    def _join_docs(self, docs: list[str]) -> str | None:
        text = self._separator.join(docs)
        text = text.strip()
        if text == "":
            return None
        return text


if __name__ == '__main__':

    _docs = [

        '梦者有特别的作用，也就是说梦是在预卜未来。因此，梦内容的',

        '梦内容的多彩多姿以及对梦者本身所遗留的特殊印象，使他们很难想象',

        '使他们很难想象出一套系统划一的观念，而需要以其个别的价值与可靠性作各',
        '值与可靠性作各种不同的分化与聚合。因此，古代哲学家们对梦的评价也就完全'
    ]
    _OVERLAP_SIZE = 1
    separator: str = "\n\n"
    _merge_docs = []
    # 将文档中page_content句子叠加的部分去掉，
    # 列表中上一个结尾与下一个开头重叠的部分，删除下一个开头重叠的部分
    _pre_doc = None
    for _doc in _docs:
        # 第一个文档直接添加
        if len(_merge_docs) == 0:
            _pre_doc = _doc
            _merge_docs.append(_doc)
            continue

        # 列表中上一个结尾与下一个开头重叠的部分，删除下一个开头重叠的部分
        # 迭代递减pre_doc的长度，每次迭代删除前面的字符，
        # 查询重叠部分，直到pre_doc的长度小于 _OVERLAP_SIZE-2len(separator)
        for _i in range(len(_pre_doc), _OVERLAP_SIZE - 2 * len(separator), -1):
            # 每次迭代删除前面的字符
            _pre_doc = _pre_doc[1:]
            if _doc[:len(_pre_doc)] == _pre_doc:
                # 删除下一个开头重叠的部分
                _page_content = _doc[len(_pre_doc):]
                _merge_docs.append(_page_content)

                _pre_doc = _doc
                break

    # 将merge_docs中的句子合并成一个文档
    _text = separator.join(_merge_docs)
    _text = _text.strip()

    logger.debug(f"merged text: {_text}")
