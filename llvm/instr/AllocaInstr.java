package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.MipsBuilder;
import mips.Register;

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
        if (isConst) {
            return values.get(Integer.valueOf(index.getName()));
        }
        else {
            return null;
        }
    }

    public Constant getValue() {
        if (isConst) {
            return values.get(0).getValue();
        }
        else {
            return null;
        }
    }

    @Override
    public int getSpace() {
        int x = getType().ptTo().size;
        if (x == -1) x = 1;
        return 4*x;
    }

    @Override
    public void toMips() {
        int x = getType().ptTo().size;
        if (x == -1) x = 1;
        memory = MipsBuilder.memory;
        MipsBuilder.memory -= 4*x;
    }

    @Override
    public void print() {
        System.out.println(name+" = alloca "+getType().ptTo().toString());
    }
}
