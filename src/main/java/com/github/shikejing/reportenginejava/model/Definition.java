package com.github.shikejing.reportenginejava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 报表定义（与具体格式版本无关的内容模型）。
 *
 * <p>这是用户提交、经校验后的内容。服务端会额外附加作用域、提交版本号、
 * 格式版本等元数据，见 {@link StoredDefinition}。
 */
public class Definition {

    /** 报表名称，同一作用域内唯一。 */
    private String name;

    /** 可选描述。 */
    private String description;

    /** 列定义，至少一列。 */
    private List<Column> columns = new ArrayList<>();

    /** 可选排序规则。 */
    private SortSpec sort;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public void setColumns(List<Column> columns) {
        this.columns = columns;
    }

    public SortSpec getSort() {
        return sort;
    }

    public void setSort(SortSpec sort) {
        this.sort = sort;
    }
}
