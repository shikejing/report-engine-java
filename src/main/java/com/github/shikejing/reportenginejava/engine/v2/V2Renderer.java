package com.github.shikejing.reportenginejava.engine.v2;

import com.github.shikejing.reportenginejava.engine.ValidatorRegistry;
import com.github.shikejing.reportenginejava.engine.v1.V1Renderer;
import com.github.shikejing.reportenginejava.model.Column;
import org.springframework.stereotype.Component;

/**
 * 格式版本 2 渲染器。
 *
 * <p>在 v1 基础上新增 STRING 列的 {@code transform}（UPPER / LOWER）能力，
 * 其余渲染行为与 v1 完全一致。历史 v1 定义永远走 v1 渲染器，语义不变。
 */
@Component
public class V2Renderer extends V1Renderer {

    public static final int FORMAT_VERSION = 2;

    public V2Renderer(ValidatorRegistry validatorRegistry) {
        super(validatorRegistry);
    }

    @Override
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    @Override
    protected String formatCell(Column col, Object value) {
        String formatted = super.formatCell(col, value);
        if (value == null || col.getType() != com.github.shikejing.reportenginejava.model.ColumnType.STRING
                || col.getTransform() == null) {
            return formatted;
        }
        if ("UPPER".equals(col.getTransform())) {
            return formatted.toUpperCase(java.util.Locale.ROOT);
        }
        if ("LOWER".equals(col.getTransform())) {
            return formatted.toLowerCase(java.util.Locale.ROOT);
        }
        return formatted;
    }
}
