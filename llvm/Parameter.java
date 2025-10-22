package llvm;

public class Parameter extends Value {

    public Parameter(IRType type) {
        super(ValueType.ARGUMENT, type, Builder.getVarName());
    }
}
