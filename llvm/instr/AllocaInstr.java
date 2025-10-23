package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;

import java.util.ArrayList;

public class AllocaInstr extends Instruction {
    private ArrayList<Value> values = new ArrayList<>();

    public AllocaInstr(IRType Type) {
        super(ValueType.ALLOCA_INST, new IRType("ptr", Type), Builder.getVarName());
        Builder.addInstr(this);
    }

    public void addValue(Value value) {
        values.add(value);
    }

    public Value getKthEle(Constant index) {
        return values.get(Integer.valueOf(index.getName()));
    }

    public Constant getValue() {
        return values.get(0).getValue();
    }

    @Override
    public void print() {
        System.out.println(name+" = alloca "+getType().ptTo().toString());
    }
}
