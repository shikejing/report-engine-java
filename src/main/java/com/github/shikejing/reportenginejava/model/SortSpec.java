package com.github.shikejing.reportenginejava.model;

/**
 * 排序规则。
 */
public class SortSpec {

    public enum Direction {
        ASC,
        DESC
    }

    /** 排序字段，必须引用 {@link Column#getField()} 中已定义的列。 */
    private String field;

    /** 排序方向，缺省 ASC。 */
    private Direction direction = Direction.ASC;

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }
}
