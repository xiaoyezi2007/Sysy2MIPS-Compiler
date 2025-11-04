package llvm.constant;

import llvm.IRType;
import llvm.ReturnType;
import llvm.ValueType;

public class ConstantInt extends Constant {
    private int value;

    public ConstantInt(Integer value) {
        super(ValueType.CONSTANT, new IRType("i32"), value.toString());
        this.value = value;
    }

    @Override
    public Constant cal(String op, Constant r) {
        ConstantInt right = (ConstantInt) r;
        if (op.equals("+")) {
            return new ConstantInt(this.value + right.value);
        }
        if (op.equals("-")) {
            return new ConstantInt(this.value - right.value);
        }
        if (op.equals("*")) {
            return new ConstantInt(this.value * right.value);
        }
        if (op.equals("/")) {
            return new ConstantInt(this.value / right.value);
        }
        if (op.equals("%")) {
            return new ConstantInt(this.value % right.value);
        }
        return this;
    }

    @Override
    public Constant getValue() {
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ConstantInt) {
            ConstantInt other = (ConstantInt) obj;
            return this.value == other.value;
        }
        return false;
    }
}
