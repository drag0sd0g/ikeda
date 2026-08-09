package com.ikeda.compound;

import java.util.List;

public record CompoundCandidate(String surface, String reading,
                                List<String> parts, List<String> shortUnits) {

    public int arity() {
        return parts.size();
    }
}
