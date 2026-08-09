package com.ikeda.compound;

import java.util.List;

public record CompoundCandidate(String surface, List<String> parts) {

    public int arity() {
        return parts.size();
    }
}
