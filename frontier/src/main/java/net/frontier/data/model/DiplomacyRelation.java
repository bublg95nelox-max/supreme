package net.frontier.data.model;

import net.frontier.api.RelationType;
import java.util.UUID;

public class DiplomacyRelation {
    private final UUID stateA;
    private final UUID stateB;
    private RelationType relation;
    private UUID declaredBy;
    private long updatedAt;
    private String terms;

    public DiplomacyRelation(UUID stateA, UUID stateB, RelationType relation, UUID declaredBy, long updatedAt, String terms) {
        this.stateA = stateA;
        this.stateB = stateB;
        this.relation = relation;
        this.declaredBy = declaredBy;
        this.updatedAt = updatedAt;
        this.terms = terms != null ? terms : "";
    }

    public UUID getStateA() {
        return stateA;
    }

    public UUID getStateB() {
        return stateB;
    }

    public RelationType getRelation() {
        return relation;
    }

    public void setRelation(RelationType relation) {
        this.relation = relation;
    }

    public UUID getDeclaredBy() {
        return declaredBy;
    }

    public void setDeclaredBy(UUID declaredBy) {
        this.declaredBy = declaredBy;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getTerms() {
        return terms;
    }

    public void setTerms(String terms) {
        this.terms = terms;
    }
}
