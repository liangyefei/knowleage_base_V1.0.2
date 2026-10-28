package com.example.project.user.query;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.QueryParam;

/**
 * 分页查询用户参数
 * @param pageNo 当前页
 * @param pageSize 每页数据量
 * @param name 用户名
 */
public record PageUserQuery (
        @QueryParam("pageNo")
        @NotNull(message = "当前页不能为空")
        @Min(value = 1, message = "当前页不能小于1")
        @Max(value = Integer.MAX_VALUE, message = "当前页不能大于" + Integer.MAX_VALUE)
        Integer pageNo,

        @QueryParam("pageSize")
        @NotNull(message = "每页数据量不能为空")
        @Min(value = 1, message = "每页数据量不能小于1")
        @Max(value = 100, message = "每页数据量不能大于100")
        Integer pageSize,

        @QueryParam("name")
        String name
) {
}
