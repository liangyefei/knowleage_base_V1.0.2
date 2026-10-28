import os
import sys

import streamlit as st
from streamlit_option_menu import option_menu

from configs import VERSION
from server.utils import api_address
from webui_pages.utils import ApiRequest
from webui_pages.dialogue.dialogue import dialogue_page, chat_box
from webui_pages.knowledge_base.knowledge_base import knowledge_base_page


api = ApiRequest(base_url=api_address())

if __name__ == "__main__":
    is_lite = "lite" in sys.argv

    st.set_page_config(
        "HaiSI RAG WebUI",
        initial_sidebar_state="expanded",
        menu_items={
            'Get Help': 'https://github.com/your-org/haisi-rag',
            'Report a bug': "https://github.com/your-org/haisi-rag/issues",
            'About': f"""欢迎使用 HaiSI RAG WebUI {VERSION}！"""
        }
    )

    pages = {
        "对话": {
            "icon": "chat",
            "func": dialogue_page,
        },
        "知识库管理": {
            "icon": "hdd-stack",
            "func": knowledge_base_page,
        },
    }

    with st.sidebar:
        st.caption(
            f"""
            <h1 align="center" style="font-size:20px">
            <font color = "#165dff">
            haisi RAG\n Test WebUI
            </h1>
            """,
            unsafe_allow_html=True,
        )
        st.caption(
            f"""
            <h4 align="center" style="font-size:15px">
            <font color = "#000000">
            当前版本：{VERSION}
            </h4>
            """,
            unsafe_allow_html=True,
        )
        options = list(pages)
        icons = [x["icon"] for x in pages.values()]

        default_index = 0
        selected_page = option_menu(
            "",
            options=options,
            icons=icons,
            default_index=default_index,
        )

    if selected_page in pages:
        pages[selected_page]["func"](api=api, is_lite=is_lite)
