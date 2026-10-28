/**
 * 文档格式类型
 */

export type DocFormatDocument = 'pdf' | 'word' | 'excel' | 'markdown' | 'txt' | 'html'
export type DocFormatCode = 'c_cpp' | 'python' | 'typescript' | 'arkts' | 'java'
export type DocFormatConfig = 'ini' | 'dtsi' | 'xml' | 'shell'
export type DocFormat = DocFormatDocument | DocFormatCode | DocFormatConfig | 'image'
