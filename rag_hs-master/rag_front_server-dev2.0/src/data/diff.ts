export interface IDiffLine {
  type: 'added' | 'removed' | 'unchanged' | 'modified'
  oldLine: number | null
  newLine: number | null
  content: string
}

export interface IDiffContent {
  oldVersion: string
  newVersion: string
  oldDocName: string
  newDocName: string
  stats: {
    added: number
    removed: number
    modified: number
  }
  sections: {
    id: string
    title: string
    lines: IDiffLine[]
  }[]
}

export const MOCK_DIFF_CONTENT: IDiffContent = {
  oldVersion: 'v2.0.0',
  newVersion: 'v2.1.0',
  oldDocName: '谛听用户手册_v2.0.0.pdf',
  newDocName: '谛听用户手册_v2.1.0.pdf',
  stats: {
    added: 42,
    removed: 18,
    modified: 7,
  },
  sections: [
    {
      id: 's1',
      title: '1. 产品概述',
      lines: [
        { type: 'unchanged', oldLine: 1, newLine: 1, content: '# 谛听 用户手册' },
        { type: 'unchanged', oldLine: 2, newLine: 2, content: '' },
        { type: 'modified', oldLine: 3, newLine: 3, content: '## 1. 产品概述' },
        { type: 'removed', oldLine: 4, newLine: null, content: '谛听是一款高性能视觉处理芯片，内置双核Cortex-A7 CPU。' },
        { type: 'added', oldLine: null, newLine: 4, content: '谛听是一款高性能视觉处理芯片，内置四核Cortex-A55 CPU和高性能NPU。' },
        { type: 'unchanged', oldLine: 5, newLine: 5, content: '' },
        { type: 'added', oldLine: null, newLine: 6, content: '### 1.1 主要特性' },
        { type: 'added', oldLine: null, newLine: 7, content: '- 四核Cortex-A55 @ 2.0GHz' },
        { type: 'added', oldLine: null, newLine: 8, content: '- 高性能NPU，4TOPS INT8算力' },
        { type: 'added', oldLine: null, newLine: 9, content: '- 双核ISP，支持8K@30fps图像处理' },
        { type: 'unchanged', oldLine: 6, newLine: 10, content: '' },
      ],
    },
    {
      id: 's2',
      title: '3. GPIO功能描述',
      lines: [
        { type: 'unchanged', oldLine: 45, newLine: 52, content: '## 3. GPIO功能描述' },
        { type: 'unchanged', oldLine: 46, newLine: 53, content: '' },
        { type: 'removed', oldLine: 47, newLine: null, content: 'GPIO控制器支持8个通用IO引脚。' },
        { type: 'added', oldLine: null, newLine: 54, content: 'GPIO控制器支持16个通用IO引脚，支持上下拉配置。' },
        { type: 'unchanged', oldLine: 48, newLine: 55, content: '' },
        { type: 'unchanged', oldLine: 49, newLine: 56, content: '### 3.1 寄存器列表' },
        { type: 'unchanged', oldLine: 50, newLine: 57, content: '' },
        { type: 'modified', oldLine: 51, newLine: 58, content: '| 寄存器名 | 地址 | 说明 |' },
        { type: 'removed', oldLine: 52, newLine: null, content: '| GPIO_DIR | 0x1A01_0000 | 方向控制 |' },
        { type: 'added', oldLine: null, newLine: 59, content: '| GPIO_DIR | 0x1A01_0000 | 方向控制寄存器 |' },
        { type: 'unchanged', oldLine: 53, newLine: 60, content: '| GPIO_DATA | 0x1A01_0004 | 数据寄存器 |' },
        { type: 'added', oldLine: null, newLine: 61, content: '| GPIO_IS | 0x1A01_0008 | 中断敏感寄存器 |' },
        { type: 'added', oldLine: null, newLine: 62, content: '| GPIO_IBE | 0x1A01_000C | 双边沿触发寄存器 |' },
      ],
    },
    {
      id: 's3',
      title: '5. NPU功能说明',
      lines: [
        { type: 'added', oldLine: null, newLine: 120, content: '## 5. NPU功能说明' },
        { type: 'added', oldLine: null, newLine: 121, content: '' },
        { type: 'added', oldLine: null, newLine: 122, content: '### 5.1 NPU概述' },
        { type: 'added', oldLine: null, newLine: 123, content: '谛听内置高性能神经网络处理器（NPU），支持INT8/FP16混合精度运算。' },
        { type: 'added', oldLine: null, newLine: 124, content: '' },
        { type: 'added', oldLine: null, newLine: 125, content: '### 5.2 技术规格' },
        { type: 'added', oldLine: null, newLine: 126, content: '- INT8算力：4 TOPS' },
        { type: 'added', oldLine: null, newLine: 127, content: '- FP16算力：2 TFLOPS' },
        { type: 'added', oldLine: null, newLine: 128, content: '- 支持算子：Conv、Pooling、ReLU、FC等' },
      ],
    },
  ],
}