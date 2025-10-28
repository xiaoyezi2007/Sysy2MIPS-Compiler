package llvm.instr;

import llvm.ReturnType;
import llvm.*;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.JInstr;
import mips.RInstr;
import mips.Register;

public class AluInstr extends Instruction {
    String op;

    public AluInstr(Value lvalue, String op, Value rvalue) {
        super(ValueType.BINARY_OPERATOR, new IRType("i32"), Builder.getVarName());
        this.op = op;
        addUseValue(lvalue);
        addUseValue(rvalue);
        Builder.addInstr(this);
    }


    @Override
    public void print() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
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
    public void toMips() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        loadToReg(lvalue, Register.T0);
        loadToReg(rvalue, Register.T1);
        if (op.equals("+")) {
            new RInstr("add", Register.T2, Register.T0, Register.T1);
            pushToMem(Register.T2);
        }
        else if (op.equals("-")) {
            new RInstr("sub", Register.T2, Register.T0, Register.T1);
            pushToMem(Register.T2);
        }
    }

    @Override
    public Constant getValue() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        if (lvalue.getValue() == null || rvalue.getValue() == null) {
            return null;
        }
        return lvalue.getValue().cal(op, rvalue.getValue());
    }
}
