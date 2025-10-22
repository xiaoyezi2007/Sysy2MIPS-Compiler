package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;

public class CmpInstr extends Instruction {
    private String op;

    public CmpInstr(Value lvalue, String op, Value rvalue) {
        super(ValueType.BINARY_OPERATOR, new IRType("i1"), Builder.getVarName());
        addUseValue(lvalue);
        addUseValue(rvalue);
        this.op = op;
        Builder.addInstr(this);
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
            lvalue.print();
        }
        if (rvalue instanceof Instruction) {
            rvalue.print();
        }
        System.out.print(name + " = icmp ");
        if (op.equals("==")) {
            System.out.print("eq");
        }
        else if (op.equals("!=")) {
            System.out.print("ne");
        }
        else if (op.equals("<")) {
            System.out.print("slt");
        }
        else if (op.equals("<=")) {
            System.out.print("sle");
        }
        else if (op.equals(">")) {
            System.out.print("sgt");
        }
        else if (op.equals(">=")) {
            System.out.print("sge");
        }
        else {
            System.out.print(op);
        }
        System.out.print(" ");
        System.out.print(lvalue.getTypeName()+" "+lvalue.getName());
        System.out.print(", ");
        System.out.println(rvalue.getName());
    }
}
