package llvm;

import java.util.ArrayList;

public class User extends Value {
    public ArrayList<Value> values = new ArrayList<>();

    public User(ValueType valueType, IRType Type, String name) {
        super(valueType, Type, name);
    }

    public void changeUse(Value from, Value to) {
        for (Use use : useList) {
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
}
