package com.github.shikejing.reportenginejava.model.error;

/**
 * 定义不存在（或当前作用域不可见）时抛出。
 *
 * <p>为避免向其他作用域泄漏定义是否存在，无论定义是真的不存在还是
 * 属于别的作用域，统一返回 404。
 */
public class DefinitionNotFoundException extends RuntimeException {

    public DefinitionNotFoundException(String scope, String name) {
        super("definition not found: " + scope + "/" + name);
    }

    public DefinitionNotFoundException(String scope, String name, int version) {
        super("definition not found: " + scope + "/" + name + " v" + version);
    }
}
