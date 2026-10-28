// EXPORTS: IRegister, IPin, MOCK_REGISTERS, MOCK_PINS

export interface IRegister {
  id: string
  name: string
  address: string
  bitField: string
  access: 'R' | 'W' | 'RW'
  resetValue: string
  description: string
  chip: string
  bitDetails: {
    bit: string
    name: string
    access: string
    reset: string
    description: string
  }[]
}

export interface IPin {
  id: string
  pinNumber: number
  pinName: string
  function: string
  muxFunction: string[]
  electrical: string
  group: string
  chip: string
}

export const MOCK_REGISTERS: IRegister[] = [
  {
    id: '1',
    name: 'GPIO_DIR',
    address: '0x1A01_0000',
    bitField: '[31:0]',
    access: 'RW',
    resetValue: '0x0000_0000',
    description: 'GPIO方向控制寄存器，每一位对应一个GPIO引脚的输入输出方向',
    chip: 'CV620',
    bitDetails: [
      { bit: '[31:8]', name: 'Reserved', access: 'RO', reset: '0x000000', description: '保留位' },
      { bit: '[7:0]', name: 'DIR[7:0]', access: 'RW', reset: '0x00', description: 'GPIO方向控制：0=输入，1=输出' },
    ],
  },
  {
    id: '2',
    name: 'GPIO_DATA',
    address: '0x1A01_0004',
    bitField: '[31:0]',
    access: 'RW',
    resetValue: '0x0000_0000',
    description: 'GPIO数据寄存器，读取引脚电平或设置输出电平',
    chip: 'CV620',
    bitDetails: [
      { bit: '[31:8]', name: 'Reserved', access: 'RO', reset: '0x000000', description: '保留位' },
      { bit: '[7:0]', name: 'DATA[7:0]', access: 'RW', reset: '0x00', description: 'GPIO数据位' },
    ],
  },
  {
    id: '3',
    name: 'NPU_CTRL',
    address: '0x1A00_0000',
    bitField: '[31:0]',
    access: 'RW',
    resetValue: '0x0000_0000',
    description: 'NPU控制寄存器，控制NPU核心的启动、复位和时钟',
    chip: 'CV620',
    bitDetails: [
      { bit: '[31:28]', name: 'VERSION', access: 'RO', reset: '0x2', description: 'NPU版本号' },
      { bit: '[27:1]', name: 'Reserved', access: 'RO', reset: '0x0000000', description: '保留位' },
      { bit: '[0]', name: 'ENABLE', access: 'RW', reset: '0x0', description: 'NPU启动使能：0=复位，1=启动' },
    ],
  },
  {
    id: '4',
    name: 'UART_THR',
    address: '0x1A02_0000',
    bitField: '[7:0]',
    access: 'W',
    resetValue: '0x00',
    description: 'UART发送保持寄存器，写入数据将被发送',
    chip: 'CV620',
    bitDetails: [
      { bit: '[7:0]', name: 'THR', access: 'WO', reset: '0x00', description: '发送数据' },
    ],
  },
  {
    id: '5',
    name: 'UART_LSR',
    address: '0x1A02_0014',
    bitField: '[7:0]',
    access: 'R',
    resetValue: '0x60',
    description: 'UART线状态寄存器，指示发送/接收状态',
    chip: 'CV620',
    bitDetails: [
      { bit: '[7]', name: 'RFE', access: 'RO', reset: '0', description: '接收FIFO错误' },
      { bit: '[6]', name: 'TEMT', access: 'RO', reset: '1', description: '发送器空' },
      { bit: '[5]', name: 'THRE', access: 'RO', reset: '1', description: '发送保持寄存器空' },
      { bit: '[0]', name: 'DR', access: 'RO', reset: '0', description: '数据就绪' },
    ],
  },
  {
    id: '6',
    name: 'I2C_CON',
    address: '0x1A03_0000',
    bitField: '[31:0]',
    access: 'RW',
    resetValue: '0x0000_0000',
    description: 'I2C控制寄存器，配置I2C总线工作模式',
    chip: 'CV610',
    bitDetails: [
      { bit: '[31:7]', name: 'Reserved', access: 'RO', reset: '0x0000000', description: '保留位' },
      { bit: '[6]', name: 'MASTER', access: 'RW', reset: '0', description: '主模式使能' },
      { bit: '[5]', name: 'SPEED', access: 'RW', reset: '0', description: '速度选择：0=标准100k，1=高速400k' },
      { bit: '[0]', name: 'ENABLE', access: 'RW', reset: '0', description: 'I2C使能' },
    ],
  },
]

export const MOCK_PINS: IPin[] = [
  { id: '1', pinNumber: 1, pinName: 'VDD_CORE', function: '核心电源', muxFunction: [], electrical: '0.9V, 500mA', group: '电源', chip: 'CV620' },
  { id: '2', pinNumber: 2, pinName: 'VDD_IO', function: 'IO电源', muxFunction: [], electrical: '3.3V, 200mA', group: '电源', chip: 'CV620' },
  { id: '3', pinNumber: 5, pinName: 'GPIO0', function: '通用IO', muxFunction: ['UART0_TX', 'SPI0_CLK'], electrical: '3.3V LVCMOS, 上拉', group: 'GPIO', chip: 'CV620' },
  { id: '4', pinNumber: 6, pinName: 'GPIO1', function: '通用IO', muxFunction: ['UART0_RX', 'SPI0_MOSI'], electrical: '3.3V LVCMOS, 上拉', group: 'GPIO', chip: 'CV620' },
  { id: '5', pinNumber: 7, pinName: 'GPIO2', function: '通用IO', muxFunction: ['I2C0_SCL', 'PWM0'], electrical: '3.3V LVCMOS, 浮空', group: 'GPIO', chip: 'CV620' },
  { id: '6', pinNumber: 8, pinName: 'GPIO3', function: '通用IO', muxFunction: ['I2C0_SDA', 'PWM1'], electrical: '3.3V LVCMOS, 浮空', group: 'GPIO', chip: 'CV620' },
  { id: '7', pinNumber: 12, pinName: 'UART0_TXD', function: 'UART发送', muxFunction: ['GPIO4'], electrical: '3.3V LVCMOS', group: '通信接口', chip: 'CV620' },
  { id: '8', pinNumber: 13, pinName: 'UART0_RXD', function: 'UART接收', muxFunction: ['GPIO5'], electrical: '3.3V LVCMOS, 上拉', group: '通信接口', chip: 'CV620' },
  { id: '9', pinNumber: 15, pinName: 'SPI0_CLK', function: 'SPI时钟', muxFunction: ['GPIO6'], electrical: '3.3V LVCMOS', group: '通信接口', chip: 'CV620' },
  { id: '10', pinNumber: 16, pinName: 'SPI0_MOSI', function: 'SPI主出从入', muxFunction: ['GPIO7'], electrical: '3.3V LVCMOS', group: '通信接口', chip: 'CV620' },
  { id: '11', pinNumber: 20, pinName: 'XTAL_IN', function: '晶振输入', muxFunction: [], electrical: '24MHz, 1.8V', group: '时钟', chip: 'CV620' },
  { id: '12', pinNumber: 21, pinName: 'XTAL_OUT', function: '晶振输出', muxFunction: [], electrical: '24MHz, 1.8V', group: '时钟', chip: 'CV620' },
]
