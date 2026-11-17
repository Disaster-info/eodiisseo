package audit.collection.fixture;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="projection_tx_probe")
public class ProjectionTxProbe {
    @Id public UUID id;
    public ProjectionTxProbe(){}
    public ProjectionTxProbe(UUID id){this.id=id;}
}