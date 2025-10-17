package llvm;

import java.util.ArrayList;

public class Value {
    private ValueType valueType;
    protected ReturnType Type;
    protected String name;
    protected ArrayList<Use> useList;
    protected ArrayList<User> userList;

    public Value(ValueType valueType, ReturnType Type, String name) {
        this.valueType = valueType;
        this.Type = Type;
        this.name = name;
    }

    public void addUser(User user) {
        userList.add(user);
    }

    public void addUse(Use use) {
        useList.add(use);
    }
}
