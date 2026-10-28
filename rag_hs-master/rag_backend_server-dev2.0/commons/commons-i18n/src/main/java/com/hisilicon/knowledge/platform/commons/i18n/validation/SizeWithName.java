package com.hisilicon.knowledge.platform.commons.i18n.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 校验长度的注解，在原有jakarta的Size基础上，增加字段名
 */
@Target({ METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE })
@Retention(RUNTIME)
@Repeatable(SizeWithName.List.class)
@Documented
@Constraint(validatedBy = {SizeWithNameValidator.class})
public @interface SizeWithName {
    String fieldName();

    String message() default "{fieldName}的长度必须在{min}和{max}之间";

    Class<?>[] groups() default { };

    Class<? extends Payload>[] payload() default { };

    /**
     * @return size the element must be higher or equal to
     */
    int min() default 0;

    /**
     * @return size the element must be lower or equal to
     */
    int max() default Integer.MAX_VALUE;

    /**
     * Defines several {@link SizeWithName} annotations on the same element.
     *
     * @see SizeWithName
     */
    @Target({ METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE })
    @Retention(RUNTIME)
    @Documented
    @interface List {
        SizeWithName[] value();
    }
}

