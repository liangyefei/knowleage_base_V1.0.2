
from langchain.schema import Document

from server.knowledge_base.kb_service.base import KBService


class DefaultKBService(KBService):
    @staticmethod
    def do_create_kb():
        pass

    @staticmethod
    def do_drop_kb():
        pass

    @staticmethod
    def do_add_doc(docs: list[Document]):
        pass

    @staticmethod
    def do_clear_vs():
        pass

    @staticmethod
    def vs_type() -> str:
        return "default"

    @staticmethod
    def do_init():
        pass

    @staticmethod
    def do_search():
        pass

    @staticmethod
    def do_insert_multi_knowledge():
        pass

    @staticmethod
    def do_insert_one_knowledge():
        pass

    @staticmethod
    def do_delete_doc():
        pass
