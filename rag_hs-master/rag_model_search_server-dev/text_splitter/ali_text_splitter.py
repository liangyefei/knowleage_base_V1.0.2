import re

from langchain.text_splitter import CharacterTextSplitter

from configs import logger


class AliTextSplitter(CharacterTextSplitter):
    def __init__(self, pdf: bool = False, **kwargs):
        super().__init__(**kwargs)
        self.pdf = pdf

    def split_text(self, text: str) -> list[str]:
        # use_document_segmentation参数指定是否用语义切分文档，此处采取的文档语义分割模型为达摩院开源的nlp_bert_document-segmentation_chinese-base，论文见https://arxiv.org/abs/2107.09278  # pylint: disable=line-too-long
        # 如果使用模型进行文档语义切分，那么需要安装modelscope[nlp]：pip install "modelscope[nlp]" -f https://modelscope.oss-cn-beijing.aliyuncs.com/releases/repo.html  # pylint: disable=line-too-long
        # 考虑到使用了三个模型，可能对于低配置gpu不太友好，因此这里将模型load进cpu计算，有需要的话可以替换device为自己的显卡id
        if self.pdf:
            text = re.sub(r"\n{3,}", r"\n", text)
            text = re.sub(r'\s', " ", text)
            text = re.sub("\n\n", "", text)
        try:
            from modelscope.pipelines import pipeline
        except ImportError as e:
            raise ImportError(
                "Could not import modelscope python package. "
                "Please install modelscope with `pip install modelscope`. "
            ) from e

        p = pipeline(
            task="document-segmentation",
            model='damo/nlp_bert_document-segmentation_chinese-base',
            device="cpu")
        result = p(documents=text)

        logger.debug(f"result.text：{result['text']}")

        sent_list=[]
        for substring in result:
            sent_list.extend(substring.split('\n\t'))
        logger.debug(f"ALI split result: {sent_list}")
        return sent_list
