package llvm;

import java.util.ArrayList;

public class User extends Value {
    public ArrayList<Value> values = new ArrayList<>();

    public User(ValueType valueType, IRType Type, String name) {
        super(valueType, Type, name);
    }

    public void changeUse(Value from, Value to) {
        for (Use use : useList) {
            to.addUser(this);
            if (use.getValue().equals(from)) {
                use.setValue(to);
            }
        }
    }

    public void addUseValue(Value value) {
        values.add(value);
        if (value != null) {
            value.addUser(this);
        }
        addUse(new Use(this, value));
        addToUseValueList(value);
    }

    public void setUseValue(int i, Value value) {
        values.set(i, value);
        useList.get(i).setValue(value);
    }

    public ArrayList<Value> getOperands() {
        ArrayList<Value> operands = new ArrayList<>();
        for (Use use : useList) {
            operands.add(use.getValue());
        }
        return operands;
    }

    public void printUse() {
        System.out.print("User:");
        for (User user : userList) {
            System.out.print(user.getName()+" ");
        }
        System.out.println();
        System.out.print("Operands:");
        ArrayList<Value> operands = getOperands();
        for (Value value : operands) {
            System.out.print(value.getName()+" ");
        }
        System.out.println();
    }
}
