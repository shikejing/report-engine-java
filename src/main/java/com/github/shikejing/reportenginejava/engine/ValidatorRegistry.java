package com.github.shikejing.reportenginejava.engine;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验器注册表：按定义格式版本选择校验器。
 */
@Component
public class ValidatorRegistry {

    private final Map<Integer, DefinitionValidator> validators = new HashMap<>();

    public ValidatorRegistry(List<DefinitionValidator> validatorList) {
        for (DefinitionValidator v : validatorList) {
            DefinitionValidator prev = validators.put(v.formatVersion(), v);
            if (prev != null) {
                throw new IllegalStateException("duplicate validator for formatVersion " + v.formatVersion());
            }
        }
    }

    public DefinitionValidator get(int formatVersion) {
        DefinitionValidator v = validators.get(formatVersion);
        if (v == null) {
            throw new IllegalStateException("no validator registered for formatVersion " + formatVersion);
        }
        return v;
    }

    public boolean supports(int formatVersion) {
        return validators.containsKey(formatVersion);
    }
}
