package com.flowops.domain;

/**
 * Membership roles, ordered {@code OWNER > ADMIN > MEMBER > VIEWER}.
 *
 * <p>Checks compare rank, so "requires ADMIN" means ADMIN or OWNER. Exactly one
 * OWNER exists per organization in M1 (its creator).
 */
public enum Role {

    VIEWER(1),
    MEMBER(2),
    ADMIN(3),
    OWNER(4),
    PLATFORM_ADMIN(5);

    private final int rank;

    Role(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** {@code true} when this role satisfies a requirement of {@code required}. */
    public boolean atLeast(Role required) {
        return this.rank >= required.rank;
    }
}
