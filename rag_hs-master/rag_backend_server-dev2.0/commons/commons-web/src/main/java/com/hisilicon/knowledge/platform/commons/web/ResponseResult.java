package com.hisilicon.knowledge.platform.commons.web;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;

public class ResponseResult<T extends Object> {
    private final String code;
    private final T data;
    private final String message;

    private ResponseResult(String code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public String getCode() { return code; }
    public T getData() { return data; }
    public String getMessage() { return message; }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * 快速构建国际化成功响应（通用模式，推荐新接口使用）
     *
     * @param data   返回数据
     * @param locale 语言环境（从 Accept-Language 获取）
     * @param <T>    数据类型
     * @return 响应对象
     */
    public static <T> ResponseResult<T> ok(T data, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        return new Builder<T>()
                .code(ErrorCodeEnum.OK.getCode())
                .message(appMessages.operationSuccessful())
                .data(data)
                .build();
    }

    public static final class Builder<T extends Object> {
        private String code;
        private T data;
        private String message;

        /**
         * 操作成功
         * @return 构造器自己
         */
        public Builder<T> ok() {
            this.code = ErrorCodeEnum.OK.getCode();
            this.message = ErrorCodeEnum.OK.getDescribe();
            return this;
        }

        /**
         * 操作成功
         * @param locale 语言环境
         * @return 构造器自己
         */
        public Builder<T> ok(String locale) {
            AppMessages appMessages = LocaleUtil.getAppMessage(locale);
            this.code = ErrorCodeEnum.OK.getCode();
            this.message = appMessages.operationSuccessful();
            return this;
        }

        /**
         * 设置响应码
         * @param code 响应码
         * @return 构造器自己
         */
        public Builder<T> code(String code) {
            this.code = code;
            return this;
        }

        /**
         * 设置返回数据
         * @param data 返回的数据
         * @return 构造器自己
         */
        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        /**
         * 构建错误信息
         * @param errorCodeEnum 错误信息枚举
         * @return 构造器自己
         */
        public Builder<T> error(ErrorCodeEnum errorCodeEnum) {
            this.code = errorCodeEnum.getCode();
            this.message = errorCodeEnum.getDescribe();
            return this;
        }

        /**
         * 设置消息内容
         * @param message 返回的消息
         * @return 构造器自己
         */
        public Builder<T> message(String message) {
            this.message = message;
            return this;
        }

        /**
         * 构建返回信息的对象
         * @return 返回的对象信息
         */
        public ResponseResult<T> build() {
            return new ResponseResult<>(code, data, message);
        }
    }
}
