package com.hisilicon.knowledge.platform.commons.i18n;

public enum LanguageEnum {
    ZH_CN("zh-CN", "中文（简体）"),
    EN_US("en-US", "english");

    LanguageEnum(String language, String desc) {
        this.language = language;
        this.desc = desc;
    }

    private final String language;
    private final String desc;

    public String getLanguage() {
        return language;
    }

    public String getDesc() {
        return desc;
    }
}
