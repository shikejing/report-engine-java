package com.github.shikejing.reportenginejava.engine;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渲染器注册表：按定义格式版本选择渲染器。
 *
 * <p>新增格式版本时注册新渲染器即可，历史版本的定义永远走各自的渲染器，
 * 语义不发生任何变化。
 */
@Component
public class RendererRegistry {

    private final Map<Integer, Renderer> renderers = new HashMap<>();

    public RendererRegistry(List<Renderer> rendererList) {
        for (Renderer r : rendererList) {
            Renderer prev = renderers.put(r.formatVersion(), r);
            if (prev != null) {
                throw new IllegalStateException("duplicate renderer for formatVersion " + r.formatVersion());
            }
        }
    }

    public Renderer get(int formatVersion) {
        Renderer r = renderers.get(formatVersion);
        if (r == null) {
            throw new IllegalStateException("no renderer registered for formatVersion " + formatVersion);
        }
        return r;
    }
}
