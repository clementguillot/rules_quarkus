package org.acme.grpc.producer;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Panache active-record entity backing {@link ItemGrpcService} with a PostgreSQL table. */
@Entity
public class ItemEntity extends PanacheEntityBase {

  @Id public String id;

  public String name;

  public int quantity;
}
