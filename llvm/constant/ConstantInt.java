package llvm.constant;

import llvm.ReturnType;
import llvm.ValueType;

public class ConstantInt extends Constant {
    private int value;

    public ConstantInt(Integer value) {
        super(ValueType.CONSTANT, ReturnType.INTEGER, value.toString());
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
}
