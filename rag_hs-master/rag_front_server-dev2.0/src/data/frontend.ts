// EXPORTS: ISearchDoc, MOCK_SEARCH_DOCS, IDocDetail, MOCK_DOC_DETAILS, HOT_QUESTIONS, QUICK_ENTRIES

export interface ISearchDoc {
  id: string
  title: string
  type: 'manual' | 'code' | 'faq' | 'app-note' | 'pdf' | 'register'
  typeLabel: string
  chipModel: string[]
  summary: string
  highlight?: string[]
  updatedAt: string
  matchScore: number
}

export const MOCK_SEARCH_DOCS: ISearchDoc[] = [
  {
    id: 'doc-001',
    title: 'CV620芯片规格书V2.1',
    type: 'pdf',
    typeLabel: 'PDF',
    chipModel: ['CV620'],
    summary: 'CV620是一款面向边缘AI计算的高性能SoC芯片，集成多核CPU、高性能NPU、丰富外设接口...',
    highlight: ['CV620芯片规格书', 'NPU算力', '外设接口'],
    updatedAt: '2024-03-15',
    matchScore: 95,
  },
  {
    id: 'doc-002',
    title: 'GPIO寄存器配置手册',
    type: 'register',
    typeLabel: '寄存器',
    chipModel: ['CV620', 'CV610', 'CV720'],
    summary: 'GPIO控制器包含多个寄存器，用于配置引脚方向、输出电平、上拉下拉、中断触发方式等...',
    highlight: ['GPIO寄存器', '引脚方向配置', '中断触发方式'],
    updatedAt: '2024-02-28',
    matchScore: 88,
  },
  {
    id: 'doc-003',
    title: 'GPIO驱动开发指南',
    type: 'manual',
    typeLabel: '技术手册',
    chipModel: ['CV620', 'CV720'],
    summary: '本文档详细介绍GPIO驱动的开发流程，包括初始化、输入输出配置、中断处理、低功耗管理等...',
    highlight: ['GPIO驱动', '中断处理', '低功耗管理'],
    updatedAt: '2024-03-01',
    matchScore: 82,
  },
  {
    id: 'doc-004',
    title: 'gpio_driver.c 驱动源码',
    type: 'code',
    typeLabel: '参考代码',
    chipModel: ['CV620', 'CV610'],
    summary: 'GPIO驱动完整实现代码，包含寄存器操作、中断处理、设备树绑定等...',
    highlight: ['gpio_driver.c', '寄存器操作', '设备树绑定'],
    updatedAt: '2024-02-20',
    matchScore: 78,
  },
  {
    id: 'doc-005',
    title: 'GPIO常见问题FAQ',
    type: 'faq',
    typeLabel: 'FAQ',
    chipModel: ['CV620', 'CV720', 'CV610'],
    summary: '汇总了GPIO使用过程中的常见问题，包括引脚复用冲突、中断不触发、电平不稳定等...',
    highlight: ['GPIO常见问题', '中断不触发', '引脚复用'],
    updatedAt: '2024-03-10',
    matchScore: 72,
  },
  {
    id: 'doc-006',
    title: 'GPIO中断应用笔记',
    type: 'app-note',
    typeLabel: '应用笔记',
    chipModel: ['CV620'],
    summary: '介绍GPIO中断在实际项目中的应用方案，包括消抖处理、中断优先级配置、唤醒源设置...',
    highlight: ['GPIO中断', '消抖处理', '唤醒源'],
    updatedAt: '2024-01-25',
    matchScore: 68,
  },
  {
    id: 'doc-007',
    title: 'UART串口通信配置指南',
    type: 'manual',
    typeLabel: '技术手册',
    chipModel: ['CV620', 'CV720'],
    summary: 'UART串口配置详解，包括波特率设置、数据位、停止位、校验位、流控等参数配置...',
    highlight: ['UART配置', '波特率', '流控'],
    updatedAt: '2024-02-18',
    matchScore: 55,
  },
  {
    id: 'doc-008',
    title: '引脚复用表（Pinmux）',
    type: 'register',
    typeLabel: '寄存器',
    chipModel: ['CV620'],
    summary: 'CV620芯片所有引脚的复用功能对照表，包含GPIO、UART、SPI、I2C、PWM等多种功能...',
    highlight: ['引脚复用', 'Pinmux', '功能对照表'],
    updatedAt: '2024-03-05',
    matchScore: 60,
  },
  {
    id: 'doc-009',
    title: '时钟使能与复位控制',
    type: 'register',
    typeLabel: '寄存器',
    chipModel: ['CV620', 'CV720', 'CV610'],
    summary: '系统时钟控制寄存器说明，包括各模块时钟使能、软件复位、时钟分频配置...',
    highlight: ['时钟使能', '复位控制', '时钟分频'],
    updatedAt: '2024-02-10',
    matchScore: 65,
  },
  {
    id: 'doc-010',
    title: '低功耗GPIO唤醒方案',
    type: 'app-note',
    typeLabel: '应用笔记',
    chipModel: ['CV620', 'CV720'],
    summary: '介绍如何使用GPIO作为唤醒源，实现芯片从低功耗模式下的快速唤醒...',
    highlight: ['低功耗', 'GPIO唤醒', '快速唤醒'],
    updatedAt: '2024-01-15',
    matchScore: 50,
  },
  {
    id: 'doc-011',
    title: 'I2C总线驱动开发',
    type: 'manual',
    typeLabel: '技术手册',
    chipModel: ['CV620', 'CV610'],
    summary: 'I2C总线驱动开发指南，包含主机模式、从机模式、多主机仲裁、时钟拉伸等...',
    highlight: ['I2C驱动', '多主机仲裁', '时钟拉伸'],
    updatedAt: '2024-02-05',
    matchScore: 45,
  },
  {
    id: 'doc-012',
    title: 'SDK开发指南V3.0',
    type: 'pdf',
    typeLabel: 'PDF',
    chipModel: ['CV720'],
    summary: 'CV720 SDK完整开发指南，涵盖系统启动、驱动开发、应用开发、调试工具等...',
    highlight: ['SDK开发', '驱动开发', '调试工具'],
    updatedAt: '2024-03-20',
    matchScore: 40,
  },
]

export interface IDocDetail {
  id: string
  title: string
  type: 'manual' | 'code' | 'faq' | 'app-note' | 'pdf' | 'register'
  typeLabel: string
  chipModel: string[]
  version: string
  updatedAt: string
  author: string
  sections: { heading: string; content: string; code?: string; codeLang?: string }[]
}

export const MOCK_DOC_DETAILS: Record<string, IDocDetail> = {
  'doc-001': {
    id: 'doc-001',
    title: 'CV620芯片规格书V2.1',
    type: 'pdf',
    typeLabel: 'PDF',
    chipModel: ['CV620'],
    version: 'v2.1.0',
    updatedAt: '2024-03-15',
    author: '芯片架构组',
    sections: [
      {
        heading: '1. 产品概述',
        content: 'CV620是一款面向边缘AI计算的高性能SoC芯片，采用先进工艺制造，集成了多核CPU、高性能NPU、丰富的外设接口，适用于智能安防、工业控制、智能终端等多种场景。\n\n主要特性：\n• 多核CPU架构，主频高达1.8GHz\n• 内置NPU，INT8算力4TOPS\n• 支持多种视频编解码格式\n• 丰富的外设接口：GPIO、UART、SPI、I2C、USB、以太网等',
      },
      {
        heading: '2. NPU性能规格',
        content: 'CV620内置的神经网络处理器（NPU）专为深度学习推理优化设计，支持多种精度模式。',
        code: '// NPU算力规格\nINT8:  4 TOPS\nFP16:  2 TFLOPS\nBF16:  2 TFLOPS\nINT4:  8 TOPS',
        codeLang: 'text',
      },
      {
        heading: '3. 电气特性',
        content: '• 工作电压：核心0.8V，IO 3.3V/1.8V\n• 典型功耗：< 2W（NPU满载）\n• 工作温度：-40°C ~ +85°C\n• 封装：FCBGA 456球',
      },
    ],
  },
  'doc-002': {
    id: 'doc-002',
    title: 'GPIO寄存器配置手册',
    type: 'register',
    typeLabel: '寄存器',
    chipModel: ['CV620', 'CV610', 'CV720'],
    version: 'v1.3.0',
    updatedAt: '2024-02-28',
    author: '硬件驱动组',
    sections: [
      {
        heading: 'GPIO方向寄存器 (GPIO_DIR)',
        content: '偏移地址：0x000\n复位值：0x00000000\n\n该寄存器用于配置GPIO引脚的输入输出方向。',
        code: '// GPIO_DIR 寄存器位域\nbit[31:0]  GPIO_DIRn  0=输入  1=输出',
        codeLang: 'c',
      },
      {
        heading: 'GPIO数据寄存器 (GPIO_DATA)',
        content: '偏移地址：0x004\n复位值：0x00000000\n\n当引脚配置为输出时，写入该寄存器的值将输出到对应引脚；当配置为输入时，读取该寄存器可获取引脚电平状态。',
      },
      {
        heading: 'GPIO中断使能寄存器 (GPIO_IE)',
        content: '偏移地址：0x008\n复位值：0x00000000\n\n用于使能/禁用各引脚的中断功能。置1使能对应引脚中断，置0禁用。',
      },
    ],
  },
  'doc-003': {
    id: 'doc-003',
    title: 'GPIO驱动开发指南',
    type: 'manual',
    typeLabel: '技术手册',
    chipModel: ['CV620', 'CV720'],
    version: 'v2.0.0',
    updatedAt: '2024-03-01',
    author: '驱动开发组',
    sections: [
      {
        heading: 'GPIO初始化流程',
        content: 'GPIO驱动初始化需按以下步骤进行：\n1. 使能GPIO模块时钟\n2. 配置引脚复用功能为GPIO\n3. 设置引脚方向（输入/输出）\n4. 配置上拉/下拉（可选）\n5. 如需中断，配置中断触发方式并使能',
      },
      {
        heading: 'GPIO输入输出示例',
        content: '以下是GPIO基本输入输出操作的示例代码：',
        code: '// 设置GPIO0为输出并输出高电平\ngpio_set_direction(GPIO0, GPIO_DIR_OUTPUT);\ngpio_set_level(GPIO0, 1);\n\n// 设置GPIO1为输入并读取电平\ngpio_set_direction(GPIO1, GPIO_DIR_INPUT);\nint level = gpio_get_level(GPIO1);',
        codeLang: 'c',
      },
    ],
  },
  'doc-004': {
    id: 'doc-004',
    title: 'gpio_driver.c 驱动源码',
    type: 'code',
    typeLabel: '参考代码',
    chipModel: ['CV620', 'CV610'],
    version: 'v1.0.5',
    updatedAt: '2024-02-20',
    author: '驱动开发组',
    sections: [
      {
        heading: 'gpio_driver.c - 完整源码',
        content: 'GPIO驱动核心实现文件，包含寄存器操作、中断处理、设备树绑定等功能。',
        code: `#include <linux/module.h>
#include <linux/gpio.h>
#include <linux/interrupt.h>
#include <linux/of.h>
#include <linux/platform_device.h>

#define GPIO_DIR_REG  0x00
#define GPIO_DATA_REG 0x04
#define GPIO_IE_REG   0x08

struct cv620_gpio {
    void __iomem *base;
    struct gpio_chip chip;
    int irq;
};

static int cv620_gpio_dir_out(struct gpio_chip *chip,
                              unsigned offset, int value)
{
    struct cv620_gpio *gpio = gpiochip_get_data(chip);
    u32 val;

    val = readl(gpio->base + GPIO_DIR_REG);
    val |= BIT(offset);
    writel(val, gpio->base + GPIO_DIR_REG);

    val = readl(gpio->base + GPIO_DATA_REG);
    if (value)
        val |= BIT(offset);
    else
        val &= ~BIT(offset);
    writel(val, gpio->base + GPIO_DATA_REG);

    return 0;
}

static int cv620_gpio_get(struct gpio_chip *chip, unsigned offset)
{
    struct cv620_gpio *gpio = gpiochip_get_data(chip);
    return !!(readl(gpio->base + GPIO_DATA_REG) & BIT(offset));
}

static void cv620_gpio_set(struct gpio_chip *chip,
                           unsigned offset, int value)
{
    struct cv620_gpio *gpio = gpiochip_get_data(chip);
    u32 val;

    val = readl(gpio->base + GPIO_DATA_REG);
    if (value)
        val |= BIT(offset);
    else
        val &= ~BIT(offset);
    writel(val, gpio->base + GPIO_DATA_REG);
}

static const struct of_device_id cv620_gpio_of_match[] = {
    { .compatible = "cv620,gpio" },
    { /* sentinel */ }
};
MODULE_DEVICE_TABLE(of, cv620_gpio_of_match);`,
        codeLang: 'c',
      },
    ],
  },
}

export const CHIP_MODELS_FRONTEND = ['全部芯片', 'CV620', 'CV610', 'CV720', 'CV520', 'Hi3516DV500']

export const DOC_TYPE_FILTERS = [
  { value: 'all', label: '全部类型' },
  { value: 'manual', label: '技术手册' },
  { value: 'code', label: '参考代码' },
  { value: 'faq', label: 'FAQ问答' },
  { value: 'app-note', label: '应用笔记' },
  { value: 'pdf', label: 'PDF文档' },
  { value: 'register', label: '寄存器手册' },
]

export const HOT_QUESTIONS = [
  'home.hotQ1',
  'home.hotQ2',
  'home.hotQ3',
  'home.hotQ4',
]

export const QUICK_ENTRIES = [
  { title: 'home.entry.chipSpec', desc: 'home.entry.chipSpecDesc', icon: 'chip', searchType: 'manual' },
  { title: 'home.entry.regManual', desc: 'home.entry.regManualDesc', icon: 'register', searchType: 'register' },
  { title: 'home.entry.devGuide', desc: 'home.entry.devGuideDesc', icon: 'code', searchType: 'code' },
  { title: 'home.entry.appSolution', desc: 'home.entry.appSolutionDesc', icon: 'app', searchType: 'app-note' },
]
