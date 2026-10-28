import os
import re
import time
import uuid
from datetime import datetime, timezone

import streamlit as st
from streamlit_chatbox import ChatBox, Markdown
from streamlit_modal import Modal

from configs import DEFAULT_KNOWLEDGE_BASE, HISTORY_LEN, LLM_MODELS, PROMPT_TEMPLATES, TEMPERATURE
from server.knowledge_base.utils import LOADER_DICT
from webui_pages.utils import ApiRequest, check_error_msg, check_success_msg

_DEFAULT_MESSAGE_ID = ""
_DEFAULT_HISTORY_INDEX = -1

chat_box = ChatBox(
    assistant_avatar=os.path.join(
        "assets",
        "img",
        "haisi_icon_v2.png"
    )
)


def get_messages_history(history_len: int, content_in_expander: bool = False) -> list[dict]:
    """
    返回消息历史。
    content_in_expander控制是否返回expander元素中的内容，一般导出的时候可以选上，传入LLM的history不需要
    """

    def filter(msg):
        content = [x for x in msg["elements"] if x._output_method in ["markdown", "text"]]  # pylint: disable=protected-access
        if not content_in_expander:
            content = [x for x in content if not x._in_expander]  # pylint: disable=protected-access
        content = [x.content for x in content]

        return {
            "role": msg["role"],
            "content": "\n\n".join(content),
        }

    return chat_box.filter_history(history_len=history_len, filter=filter)


@st.cache_data
def upload_temp_docs(files, _api: ApiRequest) -> str:
    """
    将文件上传到临时目录，用于文件对话
    返回临时向量库ID
    """
    return _api.upload_temp_docs(files).get("data", {}).get("id")


def parse_command(text: str, modal: Modal) -> bool:
    """
    检查用户是否输入了自定义命令，当前支持：
    /new {session_name}。如果未提供名称，默认为“会话X”
    /del {session_name}。如果未提供名称，在会话数量>1的情况下，删除当前会话。
    /clear {session_name}。如果未提供名称，默认清除当前会话
    /help。查看命令帮助
    返回值：输入的是命令返回True，否则返回False
    """
    _CONVERSATION_IDS_KEY = 'conversation_ids'
    _CUR_CONV_NAME_KEY = 'cur_conv_name'
    m = re.match(r"/([^\s]+)\s*(.*)", text)
    if m:
        cmd, name = m.groups()
        name = name.strip()
        conv_names = chat_box.get_chat_names()
        if cmd == "help":
            modal.open()
        elif cmd == "new":
            if not name:
                i = 1
                while True:
                    name = f"会话{i}"
                    if name not in conv_names:
                        break
                    i += 1
            if name in st.session_state[_CONVERSATION_IDS_KEY]:
                st.error(f"该会话名称 “{name}” 已存在")
                time.sleep(1)
            else:
                st.session_state[_CONVERSATION_IDS_KEY][name] = uuid.uuid4().hex
                st.session_state[_CUR_CONV_NAME_KEY] = name
        elif cmd == "del":
            name = name or st.session_state.get(_CUR_CONV_NAME_KEY)
            if len(conv_names) == 1:
                st.error("这是最后一个会话，无法删除")
                time.sleep(1)
            elif not name or name not in st.session_state[_CONVERSATION_IDS_KEY]:
                st.error(f"无效的会话名称：“{name}”")
                time.sleep(1)
            else:
                st.session_state[_CONVERSATION_IDS_KEY].pop(name, None)
                chat_box.del_chat_name(name)
                st.session_state[_CUR_CONV_NAME_KEY] = ""
        elif cmd == "clear":
            chat_box.reset_history(name=name or None)
        return True
    return False


def dialogue_page(api: ApiRequest, is_lite: bool = False):  # pylint: disable=redefined-outer-name
    _CONVERSATION_IDS_KEY = 'conversation_ids'
    _FILE_CHAT_ID_KEY = 'file_chat_id'
    _USER_ID_KEY = 'user_id'
    _NLNL = '\n\n'
    _LLM_CHAT_LABEL = 'LLM 对话'
    _KB_QA_LABEL = '知识库问答'
    _FILE_CHAT_LABEL = '文件对话'
    _CUR_LLM_MODEL_KEY = 'cur_llm_model'
    _PREV_LLM_MODEL_KEY = 'prev_llm_model'
    _TEXT_KEY = 'text'
    _MESSAGE_ID_KEY = 'message_id'
    _ELLIPSIS = '...'
    _COMPLETE_KEY = 'complete'
    _ANSWER_KEY = 'answer'
    st.session_state.setdefault(_CONVERSATION_IDS_KEY, {})
    st.session_state[_CONVERSATION_IDS_KEY].setdefault(chat_box.cur_chat_name, uuid.uuid4().hex)
    st.session_state.setdefault(_FILE_CHAT_ID_KEY, None)
    # 会话级 user_id：每个浏览器会话生成一个 UUID，用于后端多租户隔离
    st.session_state.setdefault(_USER_ID_KEY, str(uuid.uuid4()))
    default_model = api.get_default_llm_model()[0]

    if not chat_box.chat_inited:
        st.toast(
            f"欢迎使用 [`HaiSI RAG`](https://github.com/your-org/haisi-rag) ! \n\n"
            f"当前运行的模型`{default_model}`, 您可以开始提问了."
        )
        chat_box.init_session()

    # 弹出自定义命令帮助信息
    modal = Modal("自定义命令", key="cmd_help", max_width="500")
    if modal.is_open():
        with modal.container():
            cmds = [x for x in parse_command.__doc__.split("\n") if x.strip().startswith("/")]
            st.write(_NLNL.join(cmds))

    with st.sidebar:
        # 多会话
        conv_names = list(st.session_state[_CONVERSATION_IDS_KEY].keys())
        index = 0
        if st.session_state.get("cur_conv_name") in conv_names:
            index = conv_names.index(st.session_state.get("cur_conv_name"))
        conversation_name = st.selectbox("当前会话：", conv_names, index=index)
        chat_box.use_chat_name(conversation_name)
        conversation_id = st.session_state[_CONVERSATION_IDS_KEY][conversation_name]

        # TODO: 对话模型与会话绑定
        def on_mode_change():
            mode = st.session_state.dialogue_mode
            text = f"已切换到 {mode} 模式。"
            if mode == _KB_QA_LABEL:
                cur_kb = st.session_state.get("selected_kb")
                if cur_kb:
                    text = f"{text} 当前知识库： `{cur_kb}`。"
            st.toast(text)

        dialogue_modes = [_LLM_CHAT_LABEL,
                        _KB_QA_LABEL,
                        _FILE_CHAT_LABEL,
                        # "搜索引擎问答",
                        # "自定义Agent问答",
                        ]
        dialogue_mode = st.selectbox("请选择对话模式：",
                                     dialogue_modes,
                                     index=0,
                                     on_change=on_mode_change,
                                     key="dialogue_mode",
                                     )

        def on_llm_change():
            if llm_model:
                config = api.get_model_config(llm_model)
                if not config.get("online_api"):  # 只有本地model_worker可以切换模型
                    st.session_state[_PREV_LLM_MODEL_KEY] = llm_model
                st.session_state[_CUR_LLM_MODEL_KEY] = st.session_state.llm_model

        def llm_model_format_func(x):
            if x in running_models:
                return f"{x} (Running)"
            return x

        running_models = list(api.list_running_models())
        available_models = []
        config_models = api.list_config_models()
        if not is_lite:
            for k, v in config_models.get("local", {}).items(): # 列出配置了有效本地路径的模型
                if (v.get("model_path_exists")
                    and k not in running_models):
                    available_models.append(k)
        for k, v in config_models.get("online", {}).items():  # 列出ONLINE_MODELS中直接访问的模型
            if not v.get("provider") and k not in running_models:
                available_models.append(k)
        llm_models = running_models + available_models
        cur_llm_model = st.session_state.get(_CUR_LLM_MODEL_KEY, default_model)
        if cur_llm_model in llm_models:
            index = llm_models.index(cur_llm_model)
        else:
            index = 0
        llm_model = st.selectbox("选择LLM模型：",
                                 llm_models,
                                 index,
                                 format_func=llm_model_format_func,
                                 on_change=on_llm_change,
                                 key="llm_model",
                                 )
        _model_changed = st.session_state.get(_PREV_LLM_MODEL_KEY) != llm_model
        _model_not_registered = (llm_model not in config_models.get("online", {})
                                 and llm_model not in config_models.get("langchain", {})
                                 and llm_model not in running_models)
        if _model_changed and not is_lite and _model_not_registered:
            with st.spinner(f"正在加载模型： {llm_model}，请勿进行操作或刷新页面"):
                prev_model = st.session_state.get(_PREV_LLM_MODEL_KEY)
                r = api.change_llm_model(prev_model, llm_model)
                error_msg = check_error_msg(r)
                if error_msg:
                    st.error(error_msg)
                else:
                    success_msg = check_success_msg(r)
                    if success_msg:
                        st.success(success_msg)
                        st.session_state[_PREV_LLM_MODEL_KEY] = llm_model

        index_prompt = {
            _LLM_CHAT_LABEL: "llm_chat",
            # "自定义Agent问答": "agent_chat",
            # "搜索引擎问答": "search_engine_chat",
            _KB_QA_LABEL: "knowledge_base_chat",
            _FILE_CHAT_LABEL: "knowledge_base_chat",
        }
        _mode_key = index_prompt.get(dialogue_mode, "")
        prompt_templates_kb_list = list(PROMPT_TEMPLATES.get(_mode_key, {}).keys())
        prompt_template_name = prompt_templates_kb_list[0]
        if "prompt_template_select" not in st.session_state:
            st.session_state.prompt_template_select = prompt_templates_kb_list[0]

        def prompt_change():
            text = f"已切换为 {prompt_template_name} 模板。"
            st.toast(text)

        prompt_template_select = st.selectbox(
            "请选择Prompt模板：",
            prompt_templates_kb_list,
            index=0,
            on_change=prompt_change,
            key="prompt_template_select",
        )
        prompt_template_name = st.session_state.prompt_template_select
        temperature = st.slider("Temperature：", 0.0, 2.0, TEMPERATURE, 0.05)
        # 深度思考开关
        deep_think = st.toggle(
            "🧠 深度思考模式",
            value=False,
            help="开启后使用 DeepSeek 等支持推理的模型，展示完整思考过程"
        )

        # 深度思考模式自动切换模型（写在 llm_model 选择框之前）
        if deep_think:
            # 优先选择 DeepSeek 模型
            recommended_model = None
            for model in LLM_MODELS:
                if "deepseek" in model.lower():
                    recommended_model = model
                    break
            if recommended_model and recommended_model != st.session_state.get(_CUR_LLM_MODEL_KEY):
                st.session_state[_CUR_LLM_MODEL_KEY] = recommended_model
                st.toast(f"🧠 已自动切换至 {recommended_model}")
        history_len = st.number_input("历史对话轮数：", 0, 20, HISTORY_LEN)

        def on_kb_change():
            st.toast(f"已加载知识库： {st.session_state.selected_kb}")

        if dialogue_mode == _KB_QA_LABEL:
            with st.expander("知识库配置", True):
                kb_list = api.list_knowledge_bases()
                index = 0
                if DEFAULT_KNOWLEDGE_BASE in kb_list:
                    index = kb_list.index(DEFAULT_KNOWLEDGE_BASE)
                selected_kb = st.selectbox(
                    "请选择知识库：",
                    kb_list,
                    index=index,
                    on_change=on_kb_change,
                    key="selected_kb",
                )
                kb_top_k = st.number_input("匹配知识条数：", 1, VECTOR_SEARCH_TOP_K, VECTOR_SEARCH_TOP_K)

                ## Bge 模型会超过1
                score_threshold = st.slider("知识匹配分数阈值：", 0.0, 1.0, float(SCORE_THRESHOLD), 0.01)
        elif dialogue_mode == _FILE_CHAT_LABEL:
            with st.expander("文件对话配置", True):
                files = st.file_uploader("上传知识文件：",
                                        [i for ls in LOADER_DICT.values() for i in ls],
                                        accept_multiple_files=True,
                                        )
                kb_top_k = st.number_input("匹配知识条数：", 1, VECTOR_SEARCH_TOP_K, VECTOR_SEARCH_TOP_K)

                ## Bge 模型会超过1
                score_threshold = st.slider("知识匹配分数阈值：", 0.0, 1.0, float(SCORE_THRESHOLD), 0.01)
                if st.button("开始上传", disabled=len(files)==0):
                    st.session_state[_FILE_CHAT_ID_KEY] = upload_temp_docs(files, api)

    # Display chat messages from history on app rerun
    chat_box.output_messages()

    chat_input_placeholder = "请输入对话内容，换行请使用Shift+Enter。输入/help查看自定义命令 "

    def on_feedback(
        feedback,
        message_id: str = _DEFAULT_MESSAGE_ID,
        history_index: int = _DEFAULT_HISTORY_INDEX,
    ):
        reason = feedback["text"]
        score_int = chat_box.set_feedback(feedback=feedback, history_index=history_index)
        api.chat_feedback(message_id=message_id,
                          user_id=st.session_state[_USER_ID_KEY],
                          score=score_int,
                          reason=reason)
        st.session_state["need_rerun"] = True

    feedback_kwargs = {
        "feedback_type": "thumbs",
        "optional_text_label": "欢迎反馈您打分的理由",
    }

    prompt = st.chat_input(chat_input_placeholder, key="prompt")
    if prompt:
        if parse_command(text=prompt, modal=modal): # 用户输入自定义命令
            st.rerun()
        else:
            history = get_messages_history(history_len)
            chat_box.user_say(prompt)
            if dialogue_mode == _LLM_CHAT_LABEL:
                chat_box.ai_say("正在思考...")
                text = ""
                message_id = ""
                r = api.chat_chat(prompt,
                                  user_id=st.session_state[_USER_ID_KEY],
                                  history=history,
                                  conversation_id=conversation_id,
                                  model=llm_model,
                                  prompt_name=prompt_template_name,
                                  temperature=temperature)
                for t in r:
                    error_msg = check_error_msg(t)  # check whether error occured
                    if error_msg:
                        st.error(error_msg)
                        break
                    text += t.get(_TEXT_KEY, "")
                    chat_box.update_msg(text)
                    message_id = t.get(_MESSAGE_ID_KEY, "")

                metadata = {
                    _MESSAGE_ID_KEY: message_id,
                }
                chat_box.update_msg(text, streaming=False, metadata=metadata)  # 更新最终的字符串，去除光标
                chat_box.show_feedback(**feedback_kwargs,
                                       key=message_id,
                                       on_submit=on_feedback,
                                       kwargs={_MESSAGE_ID_KEY: message_id, "history_index": len(chat_box.history) - 1})

            elif dialogue_mode == _KB_QA_LABEL:
                # 根据深度思考模式构建输出元素
                elements = [f"正在查询知识库 `{selected_kb}` ..."]
                if deep_think:
                    elements.append(Markdown(_ELLIPSIS, in_expander=True, title="🧠 思考过程", state=_COMPLETE_KEY))
                elements.append(Markdown(_ELLIPSIS, in_expander=True, title="知识库匹配结果", state=_COMPLETE_KEY))
                chat_box.ai_say(elements)

                text = ""
                reasoning_text = ""
                reasoning_index = 1 if deep_think else 0  # 思考过程在元素中的索引

                for d in api.knowledge_base_chat(
                        prompt,
                        user_id=st.session_state[_USER_ID_KEY],
                        knowledge_base_name=selected_kb,
                        top_k=kb_top_k,
                        score_threshold=score_threshold,
                        history=history,
                        model=llm_model,
                        prompt_name=prompt_template_name,
                        temperature=temperature,
                        deep_think=deep_think,  # 新增参数
                ):
                    error_msg = check_error_msg(d)
                    if error_msg:
                        st.error(error_msg)
                    elif d.get("type") == "reasoning":
                        # 深度思考内容流式更新
                        reasoning_text += d.get("reasoning", "")
                        chat_box.update_msg(f"```\n{reasoning_text}\n```", element_index=reasoning_index,
                                            streaming=True)
                    elif d.get(_ANSWER_KEY):
                        chunk = d.get(_ANSWER_KEY)
                        text += chunk
                        chat_box.update_msg(text, element_index=0)
                    elif d.get("type") == "reasoning_done":
                        # 思考完成，关闭流式状态
                        chat_box.update_msg(f"```\n{reasoning_text}\n```", element_index=reasoning_index,
                                            streaming=False)

                chat_box.update_msg(text, element_index=0, streaming=False)

                # 匹配结果展示（保持不变）
                source_documents = d.get("docs", [])
                content_text = ""
                for source in source_documents:
                    content_text += source[_TEXT_KEY] + _NLNL
                # 根据是否有思考过程调整匹配结果的索引
                match_index = 2 if deep_think else 1
                chat_box.update_msg(content_text, element_index=match_index, streaming=False)
            elif dialogue_mode == _FILE_CHAT_LABEL:
                if st.session_state[_FILE_CHAT_ID_KEY] is None:
                    st.error("请先上传文件再进行对话")
                    st.stop()
                chat_box.ai_say([
                    f"正在查询文件 `{st.session_state[_FILE_CHAT_ID_KEY]}` ...",
                    Markdown(_ELLIPSIS, in_expander=True, title="文件匹配结果", state=_COMPLETE_KEY),
                ])
                text = ""
                for d in api.file_chat(prompt,
                                       knowledge_id=st.session_state[_FILE_CHAT_ID_KEY],
                                       top_k=kb_top_k,
                                       score_threshold=score_threshold,
                                       history=history,
                                       model=llm_model,
                                       prompt_name=prompt_template_name,
                                       temperature=temperature):
                    error_msg = check_error_msg(d)  # check whether error occured
                    if error_msg:
                        st.error(error_msg)
                    elif d.get(_ANSWER_KEY):
                        chunk = d.get(_ANSWER_KEY)
                        text += chunk
                        chat_box.update_msg(text, element_index=0)
                chat_box.update_msg(text, element_index=0, streaming=False)
                # 根据前端需求更改了接口，下述的source中内容由str变成了dict
                source_documents = d.get("docs", [])
                content_text = ""
                for source in source_documents:
                    content_text += source[_TEXT_KEY] + _NLNL

    if st.session_state.get("need_rerun"):
        st.session_state["need_rerun"] = False
        st.rerun()

    now = datetime.now(timezone.utc).astimezone()
    with st.sidebar:

        cols = st.columns(2)
        export_btn = cols[0]
        if cols[1].button(
                "清空对话",
                use_container_width=True,
        ):
            chat_box.reset_history()
            st.rerun()

    export_btn.download_button(
        "导出记录",
        "".join(chat_box.export2md()),
        file_name=f"{now:%Y-%m-%d %H.%M}_对话记录.md",
        mime="text/markdown",
        use_container_width=True,
    )
