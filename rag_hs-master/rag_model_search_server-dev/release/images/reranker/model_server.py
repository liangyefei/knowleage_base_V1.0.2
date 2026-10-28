import torch
import os
import logging
from enum import Enum
from transformers import AutoModelForCausalLM, AutoTokenizer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class Task(Enum):
    TEXT_TO_CODE = "Given a natural language programming task, retrieve code that correctly solves or implements the task."
    CODE_TO_CODE = "Given a code snippet, retrieve code that is semantically equivalent or solves the same task."
    CODE_TO_TEXT = "Given a code snippet, retrieve the natural language description or problem statement that best matches the code."


class RerankerModel:
    def __init__(self, model_path=None):
        self.model_path = model_path or os.getenv("MODEL_PATH", "/data/models/coreb-code-reranker")
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model = None
        self.tokenizer = None
        self.PREFIX = '<|im_start|>system\nJudge whether the Document meets the requirements based on the Query and the Instruct provided. Note that the answer can only be "yes" or "no".<|im_end|>\n<|im_start|>user\n'
        self.SUFFIX = "<|im_end|>\n<|im_start|>assistant\n"
        self.max_length = 4096

    def load_model(self):
        """加载模型"""
        try:
            logger.info(f"Loading model from: {self.model_path}")
            logger.info(f"Using device: {self.device}")

            if not os.path.exists(self.model_path):
                logger.warning(f"Model directory {self.model_path} does not exist!")

            logger.info("Loading tokenizer...")
            self.tokenizer = AutoTokenizer.from_pretrained(
                self.model_path,
                trust_remote_code=True
            )

            logger.info("Loading model...")
            load_kwargs = {
                "dtype": torch.bfloat16 if torch.cuda.is_available() else torch.float32,
                "trust_remote_code": True,
                "device_map": "auto" if torch.cuda.is_available() else None,
                "torch_dtype": torch.bfloat16 if torch.cuda.is_available() else torch.float32
            }

            self.model = AutoModelForCausalLM.from_pretrained(
                self.model_path,
                **load_kwargs
            )

            if not torch.cuda.is_available():
                self.model = self.model.to(self.device)

            self.model.eval()
            logger.info("Model loaded successfully!")

            total_params = sum(p.numel() for p in self.model.parameters())
            logger.info(f"Total parameters: {total_params:,}")

            if torch.cuda.is_available():
                logger.info(f"GPU memory allocated: {torch.cuda.memory_allocated() / 1024**3:.2f} GB")
                logger.info(f"Model is on GPU: {next(self.model.parameters()).device}")

            return True

        except Exception as e:
            logger.error(f"Error loading model: {str(e)}")
            import traceback
            logger.error(traceback.format_exc())
            raise

    def score(self, query: str, document: str, task: Task) -> float:
        """计算相关性分数"""
        if self.model is None or self.tokenizer is None:
            raise RuntimeError("Model not loaded. Call load_model() first.")

        prompt = f"{self.PREFIX}<Instruct>: {task.value}\n<Query>: {query}\n<Document>: {document}{self.SUFFIX}"

        inputs = self.tokenizer(
            prompt,
            return_tensors="pt",
            truncation=True,
            max_length=self.max_length
        )

        inputs = {k: v.to(self.device) for k, v in inputs.items()}

        with torch.no_grad():
            logits = self.model(**inputs).logits[0, -1, :]

        yes_id, no_id = self._get_yes_no_ids()
        score_value = (logits[yes_id] - logits[no_id]).item()
        return score_value

    def _get_yes_no_ids(self):
        """获取 yes 和 no 的 token ID，支持多种格式"""
        yes_id = self.tokenizer.convert_tokens_to_ids("yes")
        no_id = self.tokenizer.convert_tokens_to_ids("no")

        if yes_id == self.tokenizer.unk_token_id:
            yes_id = self.tokenizer.convert_tokens_to_ids(" yes")
        if no_id == self.tokenizer.unk_token_id:
            no_id = self.tokenizer.convert_tokens_to_ids(" no")

        if yes_id == self.tokenizer.unk_token_id:
            yes_id = self.tokenizer.convert_tokens_to_ids("Yes")
        if no_id == self.tokenizer.unk_token_id:
            no_id = self.tokenizer.convert_tokens_to_ids("No")

        return yes_id, no_id


# 全局实例
reranker = RerankerModel()


def get_reranker():
    return reranker
