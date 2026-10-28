import logging
from typing import TYPE_CHECKING, Any

logger = logging.getLogger(__name__)

if TYPE_CHECKING:
    import tiktoken


class MinxChatOpenAI:

    @staticmethod
    def import_tiktoken() -> Any:
        try:
            import tiktoken
        except ImportError as e:
            raise ValueError(
                "Could not import tiktoken python package. "
                "This is needed in order to calculate get_token_ids. "
                "Please install it with `pip install tiktoken`."
            ) from e
        return tiktoken

    @staticmethod
    def get_encoding_model(self) -> tuple[str, "tiktoken.Encoding"]:
        tiktoken_ = MinxChatOpenAI.import_tiktoken()
        if self.tiktoken_model_name is not None:
            model = self.tiktoken_model_name
        else:
            model = self.model_name
            if model == "gpt-3.5-turbo":
                # gpt-3.5-turbo may change over time.
                # Returning num tokens assuming gpt-3.5-turbo-0301.
                model = "gpt-3.5-turbo-0301"
            elif model == "gpt-4":
                # gpt-4 may change over time.
                # Returning num tokens assuming gpt-4-0314.
                model = "gpt-4-0314"
        # Returns the number of tokens used by a list of messages.
        try:
            encoding = tiktoken_.encoding_for_model(model)
        except Exception:
            logger.warning("Warning: model not found. Using cl100k_base encoding.")
            model = "cl100k_base"
            encoding = tiktoken_.get_encoding(model)
        return model, encoding
