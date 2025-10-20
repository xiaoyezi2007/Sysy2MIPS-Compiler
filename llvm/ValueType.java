package llvm;

public enum ValueType {
    // Value
    ARGUMENT,        // 参数
    BASIC_BLOCK,     // 基本块

    // Value -> Constant
    CONSTANT,        // 常量标识符
    CONSTANT_DATA,   // 字面量

    // Value -> Constant -> GlobalValue
    FUNCTION,
    GLOBAL_VARIABLE,
    GLOBAL_STRING,

    // Value -> User -> Instruction
    BINARY_OPERATOR,
    COMPARE_INST,
    BRANCH_INST,
    RETURN_INST,
    STORE_INST,
    CALL_INST,
    INPUT_INST,
    OUTPUT_INST,
    ALLOCA_INST,
    LOAD_INST,
    UNARY_OPERATOR,
}
