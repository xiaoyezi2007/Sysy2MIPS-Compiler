package llvm;

public enum ReturnType {
    // Primitive types
    VOID,       // 空返回值
    LABEL,      // 标签类型

    // Derived types
    INTEGER,    // 整数类型
    FLOAT,      // 浮点数类型
    FUNCTION,   // 函数类型
    POINTER,    // 指针类型
}
