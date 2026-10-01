package com.github.shikejing.reportenginejava.engine.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.engine.v1.V1DefinitionValidator;
import com.github.shikejing.reportenginejava.model.ColumnType;
import com.github.shikejing.reportenginejava.model.error.FieldError;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 格式版本 2 的定义校验器。
 *
 * <p>相比 v1，v2 新增字符串列的 {@code transform} 能力（UPPER / LOWER）。
 * v1 的全部规则保持不变，历史 v1 定义的校验语义不受影响。
 */
@Component
public class V2DefinitionValidator extends V1DefinitionValidator {

    public static final int FORMAT_VERSION = 2;

    @Override
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    @Override
    protected void validateTransform(JsonNode transformNode, ColumnType type, String path,
                                     List<FieldError> errors) {
        if (!transformNode.isTextual()) {
            errors.add(new FieldError(path + ".transform", "transform must be a string"));
            return;
        }
        String raw = transformNode.asText();
        if (!"UPPER".equals(raw) && !"LOWER".equals(raw)) {
            errors.add(new FieldError(path + ".transform", "transform must be UPPER or LOWER"));
            return;
        }
        if (type != ColumnType.STRING) {
            errors.add(new FieldError(path + ".transform",
                    "transform is only allowed for STRING columns, but type is " + type));
        }
    }
}
