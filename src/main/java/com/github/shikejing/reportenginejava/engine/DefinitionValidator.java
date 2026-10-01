package com.github.shikejing.reportenginejava.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.model.Definition;
import com.github.shikejing.reportenginejava.model.error.ValidationException;

/**
 * 定义校验器。每个定义格式版本（formatVersion）对应一个实现。
 *
 * <p>校验器只做「只读」校验，不产生任何写入；校验失败时抛出
 * {@link ValidationException}，错误信息必须定位到具体字段路径。
 */
public interface DefinitionValidator {

    /** 该校验器支持的定义格式版本。 */
    int formatVersion();

    /**
     * 校验原始定义内容并解析为领域模型。
     *
     * @throws ValidationException 任意字段缺失、类型不符、引用不存在对象、
     *                             字段组合不合法时抛出，携带全部字段级错误
     */
    Definition validate(JsonNode content) throws ValidationException;
}
