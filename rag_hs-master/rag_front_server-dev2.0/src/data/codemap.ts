// EXPORTS: ICodeDocMap, MOCK_CODE_DOC_MAP, IDocToCodeMap, MOCK_DOC_TO_CODE_MAP

export interface ICodeDocMap {
  code: {
    filename: string
    language: string
    lines: {
      number: number
      content: string
      docRefs: string[]
    }[]
  }
  docs: {
    id: string
    title: string
    chapter: string
    summary: string
    codeLines: number[]
  }[]
}

export interface IDocToCodeMap {
  doc: {
    filename: string
    sections: {
      id: string
      title: string
      content: string
      codeRefs: string[]
    }[]
  }
  codes: {
    id: string
    filename: string
    language: string
    snippet: string
    docSectionIds: string[]
  }[]
}

export const MOCK_CODE_DOC_MAP: ICodeDocMap = {
  code: {
    filename: 'gpio_driver.c',
    language: 'c',
    lines: [
      { number: 1, content: '#include "gpio.h"', docRefs: [] },
      { number: 2, content: '#include "hw_memmap.h"', docRefs: [] },
      { number: 3, content: '', docRefs: [] },
      { number: 4, content: '/**', docRefs: ['doc-1'] },
      { number: 5, content: ' * @brief GPIO初始化函数', docRefs: ['doc-1'] },
      { number: 6, content: ' * @param port GPIO端口号', docRefs: ['doc-1'] },
      { number: 7, content: ' * @param pin 引脚号', docRefs: ['doc-1'] },
      { number: 8, content: ' * @param dir 方向：0输入，1输出', docRefs: ['doc-1'] },
      { number: 9, content: ' */', docRefs: ['doc-1'] },
      { number: 10, content: 'int gpio_init(uint8_t port, uint8_t pin, uint8_t dir)', docRefs: ['doc-1', 'doc-2'] },
      { number: 11, content: '{', docRefs: [] },
      { number: 12, content: '    uint32_t base_addr = GPIO_BASE + port * 0x1000;', docRefs: ['doc-2'] },
      { number: 13, content: '    uint32_t reg_val;', docRefs: [] },
      { number: 14, content: '', docRefs: [] },
      { number: 15, content: '    /* 使能GPIO时钟 */', docRefs: ['doc-3'] },
      { number: 16, content: '    REG_SET_BIT(CRM_BASE + CRM_GPIO_CLKEN, port);', docRefs: ['doc-3'] },
      { number: 17, content: '', docRefs: [] },
      { number: 18, content: '    /* 配置引脚方向 */', docRefs: ['doc-1'] },
      { number: 19, content: '    reg_val = REG_READ(base_addr + GPIO_DIR);', docRefs: ['doc-1', 'doc-2'] },
      { number: 20, content: '    if (dir) {', docRefs: [] },
      { number: 21, content: '        reg_val |= (1 << pin);', docRefs: [] },
      { number: 22, content: '    } else {', docRefs: [] },
      { number: 23, content: '        reg_val &= ~(1 << pin);', docRefs: [] },
      { number: 24, content: '    }', docRefs: [] },
      { number: 25, content: '    REG_WRITE(base_addr + GPIO_DIR, reg_val);', docRefs: ['doc-1'] },
      { number: 26, content: '', docRefs: [] },
      { number: 27, content: '    return 0;', docRefs: [] },
      { number: 28, content: '}', docRefs: [] },
      { number: 29, content: '', docRefs: [] },
      { number: 30, content: '/**', docRefs: ['doc-4'] },
      { number: 31, content: ' * @brief GPIO中断配置', docRefs: ['doc-4'] },
      { number: 32, content: ' */', docRefs: ['doc-4'] },
      { number: 33, content: 'int gpio_config_irq(uint8_t port, uint8_t pin, uint8_t trigger_type)', docRefs: ['doc-4'] },
      { number: 34, content: '{', docRefs: [] },
      { number: 35, content: '    uint32_t base_addr = GPIO_BASE + port * 0x1000;', docRefs: [] },
      { number: 36, content: '    uint32_t reg_val;', docRefs: [] },
      { number: 37, content: '', docRefs: [] },
      { number: 38, content: '    /* 配置中断触发类型 */', docRefs: ['doc-4'] },
      { number: 39, content: '    reg_val = REG_READ(base_addr + GPIO_IS);', docRefs: ['doc-4'] },
      { number: 40, content: '    // ... 配置逻辑', docRefs: [] },
      { number: 41, content: '', docRefs: [] },
      { number: 42, content: '    return 0;', docRefs: [] },
      { number: 43, content: '}', docRefs: [] },
    ],
  },
  docs: [
    {
      id: 'doc-1',
      title: 'GPIO方向控制',
      chapter: '第3章 GPIO功能描述',
      summary: 'GPIO方向控制寄存器GPIO_DIR用于配置每个引脚的输入输出方向，写入1表示输出，写入0表示输入。',
      codeLines: [4, 5, 6, 7, 8, 9, 10, 18, 19, 25],
    },
    {
      id: 'doc-2',
      title: 'GPIO寄存器映射',
      chapter: '第4章 寄存器说明',
      summary: 'GPIO基地址为0x1A01_0000，每个端口偏移0x1000。GPIO_DIR寄存器偏移0x00，32位可读写。',
      codeLines: [10, 12, 19],
    },
    {
      id: 'doc-3',
      title: '时钟使能控制',
      chapter: '第2章 系统控制',
      summary: '使用GPIO前必须先使能对应端口的时钟，通过CRM_GPIO_CLKEN寄存器控制。',
      codeLines: [15, 16],
    },
    {
      id: 'doc-4',
      title: 'GPIO中断配置',
      chapter: '第5章 中断功能',
      summary: 'GPIO支持上升沿、下降沿、双边沿、低电平、高电平五种中断触发方式，通过GPIO_IS和GPIO_IBE寄存器配置。',
      codeLines: [30, 31, 32, 33, 38, 39],
    },
  ],
}

export const MOCK_DOC_TO_CODE_MAP: IDocToCodeMap = {
  doc: {
    filename: 'GPIO控制器用户手册 v2.1',
    sections: [
      {
        id: 'dc-sec-1',
        title: '3.1 GPIO概述',
        content: 'GPIO（General Purpose Input/Output）通用输入输出接口是芯片与外部设备交互的基础接口。本芯片提供8个GPIO端口（Port A ~ Port H），每个端口最多支持16个引脚，共计128个可配置GPIO引脚。每个引脚可独立配置为输入或输出模式，并支持多种中断触发方式。',
        codeRefs: ['dc-code-1'],
      },
      {
        id: 'dc-sec-2',
        title: '3.2 GPIO方向配置',
        content: 'GPIO方向通过GPIO_DIR寄存器配置。向GPIO_DIR寄存器的对应位写入1表示该引脚配置为输出模式，写入0表示配置为输入模式。复位后所有引脚默认为输入模式。配置前需先通过CRM_GPIO_CLKEN寄存器使能对应端口的时钟。',
        codeRefs: ['dc-code-1', 'dc-code-2'],
      },
      {
        id: 'dc-sec-3',
        title: '3.3 GPIO输出电平控制',
        content: '当引脚配置为输出模式时，可通过GPIO_DATA寄存器控制输出电平。向GPIO_DATA对应位写入1输出高电平，写入0输出低电平。GPIO_DATA寄存器支持字节、半字、字访问方式，方便批量操作引脚电平。',
        codeRefs: ['dc-code-1'],
      },
      {
        id: 'dc-sec-4',
        title: '3.4 GPIO中断机制',
        content: '每个GPIO引脚支持5种中断触发方式：上升沿触发、下降沿触发、双边沿触发、低电平触发、高电平触发。中断触发类型通过GPIO_IS（中断感知）和GPIO_IBE（双边沿使能）寄存器配置。中断使能通过GPIO_IE寄存器控制，中断状态可通过GPIO_MIS寄存器查询。',
        codeRefs: ['dc-code-3', 'dc-code-4'],
      },
      {
        id: 'dc-sec-5',
        title: '3.5 GPIO寄存器映射表',
        content: 'GPIO基地址为0x1A01_0000，每个端口偏移0x1000。主要寄存器包括：GPIO_DIR（偏移0x00，方向控制）、GPIO_DATA（偏移0x04，数据寄存器）、GPIO_IS（偏移0x08，中断感知）、GPIO_IBE（偏移0x0C，双边沿使能）、GPIO_IE（偏移0x10，中断使能）、GPIO_MIS（偏移0x14，屏蔽中断状态）。所有寄存器均为32位可读写。',
        codeRefs: ['dc-code-2'],
      },
    ],
  },
  codes: [
    {
      id: 'dc-code-1',
      filename: 'gpio_driver.c',
      language: 'c',
      snippet: `int gpio_init(uint8_t port, uint8_t pin, uint8_t dir)
{
    uint32_t base_addr = GPIO_BASE + port * 0x1000;
    uint32_t reg_val;

    /* 使能GPIO时钟 */
    REG_SET_BIT(CRM_BASE + CRM_GPIO_CLKEN, port);

    /* 配置引脚方向 */
    reg_val = REG_READ(base_addr + GPIO_DIR);
    if (dir) {
        reg_val |= (1 << pin);
    } else {
        reg_val &= ~(1 << pin);
    }
    REG_WRITE(base_addr + GPIO_DIR, reg_val);

    return 0;
}`,
      docSectionIds: ['dc-sec-1', 'dc-sec-2', 'dc-sec-3'],
    },
    {
      id: 'dc-code-2',
      filename: 'gpio_reg.h',
      language: 'c',
      snippet: `#define GPIO_BASE        0x1A010000
#define GPIO_PORT_OFFSET  0x1000

#define GPIO_DIR_OFFSET   0x00
#define GPIO_DATA_OFFSET  0x04
#define GPIO_IS_OFFSET    0x08
#define GPIO_IBE_OFFSET   0x0C
#define GPIO_IE_OFFSET    0x10
#define GPIO_MIS_OFFSET   0x14

#define GPIO_DIR(port)    (GPIO_BASE + (port) * GPIO_PORT_OFFSET + GPIO_DIR_OFFSET)
#define GPIO_DATA(port)   (GPIO_BASE + (port) * GPIO_PORT_OFFSET + GPIO_DATA_OFFSET)`,
      docSectionIds: ['dc-sec-2', 'dc-sec-5'],
    },
    {
      id: 'dc-code-3',
      filename: 'gpio_irq.c',
      language: 'c',
      snippet: `int gpio_config_irq(uint8_t port, uint8_t pin, uint8_t trigger_type)
{
    uint32_t base_addr = GPIO_BASE + port * 0x1000;
    uint32_t reg_val;

    /* 配置中断触发类型 */
    reg_val = REG_READ(base_addr + GPIO_IS);
    if (trigger_type == TRIG_LEVEL_HIGH || trigger_type == TRIG_LEVEL_LOW) {
        reg_val |= (1 << pin);
    } else {
        reg_val &= ~(1 << pin);
    }
    REG_WRITE(base_addr + GPIO_IS, reg_val);

    return 0;
}`,
      docSectionIds: ['dc-sec-4'],
    },
    {
      id: 'dc-code-4',
      filename: 'gpio_irq.c',
      language: 'c',
      snippet: `int gpio_enable_irq(uint8_t port, uint8_t pin)
{
    uint32_t base_addr = GPIO_BASE + port * 0x1000;

    /* 使能GPIO中断 */
    REG_SET_BIT(base_addr + GPIO_IE, 1 << pin);

    /* 配置双边沿触发（如果需要） */
    REG_SET_BIT(base_addr + GPIO_IBE, 1 << pin);

    return 0;
}`,
      docSectionIds: ['dc-sec-4'],
    },
  ],
}