package com.github.homeant.data.shield.mybatis.interceptor;

import com.github.homeant.data.shield.annotation.TableField;
import com.github.homeant.data.shield.asserting.IAssert;
import com.github.homeant.data.shield.dataMasking.DataMasking;
import com.github.homeant.data.shield.helper.DataShieldHelper;
import com.github.homeant.data.shield.process.IDataProcess;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.plugin.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Intercepts({
        @Signature(
                type = ResultSetHandler.class,
                method = "handleResultSets",
                args = {Statement.class}
        )
})
@Slf4j
@Data
public class DecodeInterceptor implements Interceptor, ApplicationContextAware {

    private final IDataProcess dataProcess;

    private ApplicationContext applicationContext;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 获取到返回结果
        Object returnValue = invocation.proceed();
        if (returnValue != null) {
            // 对结果进行处理
            try {
                if (returnValue instanceof ArrayList<?>) {
                    List<?> list = (ArrayList<?>) returnValue;
                    for (int index = 0; index < list.size(); index++) {
                        Object returnItem = list.get(index);
                        if (returnItem != null) {
                            Class<?> clazz = returnItem.getClass();
                            Type superType = clazz.getGenericSuperclass();
                            if (superType.getClass().isInstance(Object.class)) {
                                List<Field> fieldList = new ArrayList<>();
                                ReflectionUtils.doWithFields(clazz, fieldList::add);
                                for (Field field : fieldList) {
                                    TableField annotation = field.getAnnotation(TableField.class);
                                    if (annotation != null) {
                                        if (annotation.decode()) {
                                            if (field.getGenericType() == String.class) {
                                                field.setAccessible(true);
                                                String value = (String) field.get(returnItem);
                                                Class<? extends IAssert>[] asserts = annotation.asserts();
                                                boolean result = true;
                                                for (int i = 0; i < asserts.length; i++) {
                                                    IAssert instance = getInstance(asserts[i]);
                                                    if (!instance.decode(value, returnItem)) {
                                                        result = false;
                                                        break;
                                                    }
                                                }
                                                if (result) {
                                                    try {
                                                        ReflectionUtils.setField(field, returnItem, dataProcess.decode(value));
                                                    } catch (Exception e) {
                                                        log.error("decode {}.{} value:{} fail", value, clazz.getName(), field.getName());
                                                        throw e;
                                                    }
                                                }
                                            }
                                        }
                                        String dataMaskingFlag = DataShieldHelper.getDataMasking();
                                        if (!StringUtils.isEmpty(dataMaskingFlag)) {
                                            Class<? extends DataMasking> dataMasking = annotation.dataMasking();
                                            if (dataMasking != DataMasking.class) {
                                                DataMasking instance = getInstance(dataMasking);
                                                field.setAccessible(true);
                                                String value = (String) field.get(returnItem);
                                                value = instance.apply(value);
                                                ReflectionUtils.setField(field, returnItem, value);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                throw e;
            }
        }
        return returnValue;
    }

    @Override
    public void setProperties(Properties properties) {

    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    private <T> T getInstance(Class<T> clazz) throws IllegalAccessException, InstantiationException {
        try {
            T bean = applicationContext.getBean(clazz);
            return bean;
        } catch (NoSuchBeanDefinitionException e) {
            return clazz.newInstance();
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
