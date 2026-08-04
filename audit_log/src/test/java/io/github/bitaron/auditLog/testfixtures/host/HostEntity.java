package io.github.bitaron.auditLog.testfixtures.host;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Stands in for an entity belonging to a hypothetical consuming application, used to prove the
 * starter's own entity scanning never crowds out the host's.
 */
@Entity
public class HostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
