package llvm.instr;

import llvm.ReturnType;
import llvm.*;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;

public class AluInstr extends Instruction {
    String op;

    public AluInstr(Value lvalue, String op, Value rvalue) {
        super(ValueType.BINARY_OPERATOR, new IRType("i32"), Builder.getVarName());
        this.op = op;
        addUseValue(lvalue);
        addUseValue(rvalue);
    }


    @Override
    public void print() {
        if (isPrint) {
            return;
        }
        isPrint = true;
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        if (lvalue instanceof Instruction) {
            ((Instruction) lvalue).print();
        }
        if (rvalue instanceof Instruction) {
            ((Instruction) rvalue).print();
        }
        System.out.print(name + " = ");
        if (op.equals("+")) {
            System.out.print("add");
        }
        else if (op.equals("-")) {
            System.out.print("sub");
        }
        else if (op.equals("*")) {
            System.out.print("mul");
        }
        else if (op.equals("/")) {
            System.out.print("sdiv");
        }
        else if (op.equals("%")) {
            System.out.print("srem");
        }
        System.out.println(" "+lvalue.getTypeName()+" "+lvalue.getName()+", "+rvalue.getName());
    }

    @Override
    public Constant getValue() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        return lvalue.getValue().cal(op, rvalue.getValue());
    }
}
