package com.hsg.relay.function.collection;

import com.hsg.relay.function.FunctionExecutionService;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.RuleList;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 集合汇总函数共用的数值提取与算法：只做 BigDecimal 运算，输入不被修改。
 */
final class CollectionNumbers {
    /** 除法的统一精度：结果保留 6 位小数，四舍五入（HALF_UP）；中间运算用 DECIMAL128。 */
    static final int DIVISION_SCALE = 6;
    static final RoundingMode DIVISION_ROUNDING = RoundingMode.HALF_UP;
    static final MathContext MC = MathContext.DECIMAL128;

    private CollectionNumbers() {
    }

    /**
     * 从集合里取出参与汇总的数值：NUMBER_LIST 取元素本身（不能指定取值属性）；OBJECT_LIST 必须指定取值属性，
     * 且该属性须为整数 / 小数类型。任何一条记录取不到数值（属性为空）都报错，不会静默跳过。
     */
    static List<BigDecimal> values(CollectionInput input, String selector, String selectorParameter) {
        String owner = input.owner;
        if (RuleList.NUMBER_LIST.equals(input.dataType)) {
            if (selector != null)
                throw new IllegalArgumentException(owner + " 的参数 " + selectorParameter + " 只用于对象集合 OBJECT_LIST；数值清单按元素本身汇总");
            List<BigDecimal> values = new ArrayList<>();
            for (Object element : input.elements()) values.add((BigDecimal) element);
            return values;
        }
        if (!input.records())
            throw new IllegalArgumentException(owner + " 的参数 " + input.code + " 应为数值清单 NUMBER_LIST 或对象集合 OBJECT_LIST，实际为 "
                    + input.describe());
        if (selector == null)
            throw new IllegalArgumentException(owner + " 的参数 " + selectorParameter + " 必填：对象集合须指定参与汇总的属性");
        ElementSchema.Attribute attribute;
        try {
            attribute = input.schema.attribute(selector);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(owner + " 的取值属性 " + selector + " 不在元素结构 " + input.schema.code() + " 里");
        }
        if (!ElementSchema.INTEGER.equals(attribute.type()) && !ElementSchema.DECIMAL.equals(attribute.type()))
            throw new IllegalArgumentException(owner + " 的取值属性 " + selector + "（" + attribute.type() + "）不是数值，不能汇总");
        List<BigDecimal> values = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> row : CollectionInput.rows(input)) {
            Object value = row.get(selector);
            if (value == null)
                throw new IllegalArgumentException(owner + " 第 " + (index + 1) + " 条记录的属性 " + selector + " 为空，不能参与汇总（不会静默忽略）");
            values.add(CollectionInput.decimal(value));
            index++;
        }
        return values;
    }

    static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) total = total.add(value, MC);
        return total;
    }

    /** 平均值：合计 ÷ 个数，保留 6 位小数、四舍五入。 */
    static BigDecimal average(List<BigDecimal> values) {
        return sum(values).divide(BigDecimal.valueOf(values.size()), DIVISION_SCALE, DIVISION_ROUNDING);
    }

    /** 最小值（minimum）或最大值；清单为空时报错（不返回 0 或 null）。 */
    static BigDecimal pick(String owner, List<BigDecimal> values, boolean minimum, String what) {
        if (values.isEmpty())
            throw new FunctionExecutionService.FunctionExecutionException(owner + "：集合为空，没有可计算的" + what);
        BigDecimal best = values.get(0);
        for (BigDecimal value : values) {
            int order = value.compareTo(best);
            if (minimum ? order < 0 : order > 0) best = value;
        }
        return best;
    }
}
