import torch
import os
import logging
from typing import List, Optional, Union
from sentence_transformers import SentenceTransformer
from transformers import AutoTokenizer, AutoModel
import torch.nn.functional as F

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class EmbeddingModel:
    def __init__(self, model_path=None):
        self.model_path = model_path or os.getenv("MODEL_PATH", "/data/models/Qwen3-Embedding-4B")
        self.model = None
        self.tokenizer = None
        self.device = None
        self.use_sentence_transformers = True
        self.prompts = None

    @staticmethod
    def similarity(query_embeddings: List[List[float]], document_embeddings: List[List[float]]) -> List[List[float]]:
        """计算查询和文档向量之间的相似度"""
        import numpy as np
        q = np.array(query_embeddings)
        d = np.array(document_embeddings)
        similarity_matrix = np.dot(q, d.T)
        return similarity_matrix.tolist()

    def load_model(self):
        """加载模型"""
        try:
            logger.info(f"Loading embedding model from: {self.model_path}")

            if torch.cuda.is_available():
                self.device = torch.device("cuda")
                logger.info(f"Using GPU: {torch.cuda.get_device_name(0)}")
            else:
                self.device = torch.device("cpu")
                logger.info("CUDA not available, using CPU")

            # 使用 SentenceTransformer 加载
            try:
                logger.info("Loading with SentenceTransformer...")
                self.model = SentenceTransformer(
                    self.model_path,
                    model_kwargs={
                        "device_map": "auto"
                    } if torch.cuda.is_available() else {},
                    tokenizer_kwargs={"padding_side": "left"},
                    device=self.device
                )
                self.use_sentence_transformers = True
                logger.info("Model loaded successfully with SentenceTransformer")

            except Exception as e:
                logger.warning(f"SentenceTransformer loading failed: {e}")
                logger.info("Falling back to Transformers loading...")

                # 备用方案：使用 Transformers
                self.tokenizer = AutoTokenizer.from_pretrained(
                    self.model_path,
                    padding_side="left",
                    trust_remote_code=True
                )
                self.model = AutoModel.from_pretrained(
                    self.model_path,
                    trust_remote_code=True,
                    torch_dtype=torch.float16 if torch.cuda.is_available() else torch.float32,
                    device_map="auto" if torch.cuda.is_available() else None
                )
                if not torch.cuda.is_available():
                    self.model = self.model.to(self.device)
                self.use_sentence_transformers = False
                logger.info("Model loaded successfully with Transformers")

            # 获取 prompts
            self.prompts = getattr(self.model, "prompts", {}) if self.use_sentence_transformers else {}
            logger.info(f"Available prompts: {list(self.prompts.keys()) if self.prompts else 'None'}")

            total_params = sum(p.numel() for p in self.model.parameters())
            logger.info(f"Total parameters: {total_params:,}")

            if torch.cuda.is_available():
                logger.info(f"GPU memory allocated: {torch.cuda.memory_allocated() / 1024**3:.2f} GB")

            return True

        except Exception as e:
            logger.error(f"Error loading model: {str(e)}")
            import traceback
            logger.error(traceback.format_exc())
            raise

    def encode(self, texts: Union[str, List[str]], prompt_name: Optional[str] = None) -> List[List[float]]:
        """编码文本为向量"""
        if self.model is None:
            raise RuntimeError("Model not loaded. Call load_model() first.")

        if isinstance(texts, str):
            texts = [texts]

        logger.info(f"Encoding {len(texts)} texts...")

        if self.use_sentence_transformers:
            embeddings = self.model.encode(
                texts,
                prompt_name=prompt_name,
                convert_to_tensor=True,
                normalize_embeddings=True
            )
            return embeddings.detach().cpu().numpy().tolist()
        else:
            return self._encode_with_transformers(texts)

    def _encode_with_transformers(self, texts: List[str]) -> List[List[float]]:
        """使用 Transformers 编码（备用方案）"""
        batch_dict = self.tokenizer(
            texts,
            padding=True,
            truncation=True,
            max_length=8192,
            return_tensors="pt"
        )
        batch_dict = {k: v.to(self.model.device) for k, v in batch_dict.items()}

        with torch.no_grad():
            outputs = self.model(**batch_dict)

        attention_mask = batch_dict['attention_mask']
        last_hidden_states = outputs.last_hidden_state

        # last_token_pool
        left_padding = (attention_mask[:, -1].sum() == attention_mask.shape[0])
        if left_padding:
            embeddings = last_hidden_states[:, -1]
        else:
            sequence_lengths = attention_mask.sum(dim=1) - 1
            batch_size = last_hidden_states.shape[0]
            embeddings = last_hidden_states[torch.arange(batch_size, device=last_hidden_states.device), sequence_lengths]

        # 归一化
        embeddings = F.normalize(embeddings, p=2, dim=1)
        return embeddings.cpu().numpy().tolist()


# 全局实例
embedding_model = EmbeddingModel()


def get_embedding_model():
    return embedding_model
