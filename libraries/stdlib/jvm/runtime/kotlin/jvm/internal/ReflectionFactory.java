/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.jvm.internal;

import kotlin.SinceKotlin;
import kotlin.reflect.*;

import java.util.List;

@SuppressWarnings("rawtypes")
public class ReflectionFactory {
    private static final String KOTLIN_JVM_FUNCTIONS = "kotlin.jvm.functions.";

    public KClass createKotlinClass(Class javaClass) {
        return new ClassReference(javaClass);
    }

    public KClass createKotlinClass(Class javaClass, String internalName) {
        return new ClassReference(javaClass);
    }

    public KDeclarationContainer getOrCreateKotlinPackage(Class javaClass, String moduleName) {
        return new PackageReference(javaClass, moduleName);
    }

    public KClass getOrCreateKotlinClass(Class javaClass) {
        return new ClassReference(javaClass);
    }

    public KClass getOrCreateKotlinClass(Class javaClass, String internalName) {
        return new ClassReference(javaClass);
    }

    @SinceKotlin(version = "1.1")
    public String renderLambdaToString(Lambda lambda) {
        return renderLambdaToString((FunctionBase) lambda);
    }

    @SinceKotlin(version = "1.3")
    public String renderLambdaToString(FunctionBase lambda) {
        String result = lambda.getClass().getGenericInterfaces()[0].toString();
        return result.startsWith(KOTLIN_JVM_FUNCTIONS) ? result.substring(KOTLIN_JVM_FUNCTIONS.length()) : result;
    }

    // Functions

    public KFunction function(FunctionReference f) {
        return f;
    }

    // Properties

    public KProperty0 property0(PropertyReference0 p) {
        return p;
    }

    public KMutableProperty0 mutableProperty0(MutablePropertyReference0 p) {
        return p;
    }

    public KProperty1 property1(PropertyReference1 p) {
        return p;
    }

    public KMutableProperty1 mutableProperty1(MutablePropertyReference1 p) {
        return p;
    }

    public KProperty2 property2(PropertyReference2 p) {
        return p;
    }

    public KMutableProperty2 mutableProperty2(MutablePropertyReference2 p) {
        return p;
    }

    // typeOf

    @SinceKotlin(version = "1.4")
    public KType typeOf(KClassifier klass, List<KTypeProjection> arguments, boolean isMarkedNullable) {
        return new TypeReference(klass, arguments, isMarkedNullable);
    }

    @SinceKotlin(version = "1.4")
    public KTypeParameter typeParameter(Object container, String name, KVariance variance, boolean isReified) {
        return new TypeParameterReference(container, name, variance, isReified);
    }

    @SinceKotlin(version = "1.4")
    public void setUpperBounds(KTypeParameter typeParameter, List<KType> bounds) {
        ((TypeParameterReference) typeParameter).setUpperBounds(bounds);
    }

    @SinceKotlin(version = "1.6")
    public KType platformType(KType lowerBound, KType upperBound) {
        return new TypeReference(
                lowerBound.getClassifier(), lowerBound.getArguments(), lowerBound.isMarkedNullable(),
                upperBound,
                ((TypeReference) lowerBound).getMutableCollectionType$kotlin_stdlib()
        );
    }

    @SinceKotlin(version = "1.6")
    public KType mutableCollectionType(KType type) {
        return new TypeReference(
                type.getClassifier(), type.getArguments(), type.isMarkedNullable(),
                ((TypeReference) type).getPlatformTypeUpperBound$kotlin_stdlib(),
                true
        );
    }
}
