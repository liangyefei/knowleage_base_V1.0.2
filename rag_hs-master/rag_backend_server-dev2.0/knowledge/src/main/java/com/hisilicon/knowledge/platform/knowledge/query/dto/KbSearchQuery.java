package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 知识库搜索入参
 *
 * @param kbName    知识库名称（模糊，可选）
 * @param status    知识库状态（精准，可选，默认 1）
 * @param createdBy 创建者账号（模糊，可选）
 * @param page      页码（默认 1）
 * @param pageSize  每页条数（默认 20）
 */
public record KbSearchQuery(String kbName, Short status, String createdBy, Integer page, Integer pageSize) {

    public KbSearchQuery {
        status = (status == null) ? 1 : status;
        page = (page == null || page < 1) ? 1 : page;
        pageSize = (pageSize == null || pageSize < 1) ? 20 : pageSize;
    }
}
