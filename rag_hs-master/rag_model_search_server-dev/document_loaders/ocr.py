# pylint: disable=redefined-outer-name
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    try:
        from rapidocr_paddle import RapidOCR
    except ImportError:
        from rapidocr_onnxruntime import RapidOCR

# 获取OCR模型，目前已经用vlm替代了，但是代码暂时保留，方便后续扩展。
def get_ocr(use_cuda: bool = True) -> "RapidOCR":
    try:
        from rapidocr_paddle import RapidOCR as _RapidOCR
        ocr = _RapidOCR(det_use_cuda=use_cuda, cls_use_cuda=use_cuda, rec_use_cuda=use_cuda)
    except ImportError:
        from rapidocr_onnxruntime import RapidOCR as _RapidOCR
        ocr = _RapidOCR()
    return ocr
