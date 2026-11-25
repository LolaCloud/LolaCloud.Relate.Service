package com.miranda.luna.relate.infrastructure.docker;

import com.miranda.luna.relate.domain.model.DatabaseInstance;
import com.miranda.luna.relate.domain.model.EngineType;
import com.miranda.luna.relate.domain.model.InstanceTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DockerProvisionerServiceTest {

    @Mock
    private DockerCommandRunner commandRunner;

    private DockerProvisionerService provisionerService;

    @BeforeEach
    void setUp() {
        provisionerService = new DockerProvisionerService(true, false, 60, commandRunner);
    }

    @Test
    void testIsDockerAvailableTrue() throws Exception {
        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(0, "CONTAINER ID IMAGE ...", ""));

        assertTrue(provisionerService.isDockerAvailable());
    }

    @Test
    void testIsDockerAvailableFalseOnNonZeroExit() throws Exception {
        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(1, "", "permission denied"));

        assertFalse(provisionerService.isDockerAvailable());
    }

    @Test
    void testIsDockerAvailableFalseOnException() throws Exception {
        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenThrow(new IOException("Cannot run program docker"));

        assertFalse(provisionerService.isDockerAvailable());
    }

    @Test
    void testIsDockerAvailableFalseWhenDisabled() {
        DockerProvisionerService disabledService = new DockerProvisionerService(false, false, 60, commandRunner);
        assertFalse(disabledService.isDockerAvailable());
    }

    @Test
    void testProvisionPostgresContainerSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        DatabaseInstance instance = DatabaseInstance.builder()
                .id(id)
                .name("test-postgres")
                .engine(EngineType.POSTGRESQL)
                .version("17")
                .internalPort(15432)
                .adminUsername("pguser")
                .adminPassword("SecretPass123!")
                .defaultDatabase("test_db")
                .tier(InstanceTier.STANDARD_1GB)
                .build();

        // Mock docker ps
        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(0, "", ""));

        // Mock docker run returning 64-char container ID
        String fullContainerId = "c843f5d1e2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
        when(commandRunner.run(anyList(), eq(60L)))
                .thenReturn(new CommandResult(0, fullContainerId + "\n", ""));

        String containerId = provisionerService.provisionContainer(instance);

        assertEquals("c843f5d1e2a3", containerId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandRunner).run(captor.capture(), eq(60L));
        List<String> cmd = captor.getValue();

        assertEquals("docker", cmd.get(0));
        assertEquals("run", cmd.get(1));
        assertEquals("-d", cmd.get(2));
        assertTrue(cmd.contains("--name"));
        assertTrue(cmd.contains("lola-relate-db-test-postgres"));
        assertTrue(cmd.contains("-p"));
        assertTrue(cmd.contains("127.0.0.1:15432:5432"));
        assertTrue(cmd.contains("-v"));
        assertTrue(cmd.contains("lola_relate_data_test-postgres:/var/lib/postgresql/data"));
        assertTrue(cmd.contains("-m"));
        assertTrue(cmd.contains("1024m"));
        assertTrue(cmd.contains("--label"));
        assertTrue(cmd.contains("lola.service=relate"));
        assertTrue(cmd.contains("lola.relate.instance-id=" + id));
        assertTrue(cmd.contains("lola.relate.instance-name=test-postgres"));
        assertTrue(cmd.contains("lola.relate.lrn=lola:relate:instance:" + id));
        assertTrue(cmd.contains("POSTGRES_USER=pguser"));
        assertTrue(cmd.contains("POSTGRES_PASSWORD=SecretPass123!"));
        assertTrue(cmd.contains("POSTGRES_DB=test_db"));
        assertEquals("postgres:17-alpine", cmd.get(cmd.size() - 1));
    }

    @Test
    void testProvisionMySqlContainerSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        DatabaseInstance instance = DatabaseInstance.builder()
                .id(id)
                .name("test-mysql")
                .engine(EngineType.MYSQL)
                .version("8.0")
                .internalPort(15433)
                .adminUsername("root")
                .adminPassword("MySqlPass456!")
                .defaultDatabase("mysql_db")
                .tier(InstanceTier.MICRO_512MB)
                .build();

        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(0, "", ""));

        when(commandRunner.run(anyList(), eq(60L)))
                .thenReturn(new CommandResult(0, "a1b2c3d4e5f6\n", ""));

        String containerId = provisionerService.provisionContainer(instance);
        assertEquals("a1b2c3d4e5f6", containerId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandRunner).run(captor.capture(), eq(60L));
        List<String> cmd = captor.getValue();

        assertTrue(cmd.contains("lola-relate-db-test-mysql"));
        assertTrue(cmd.contains("127.0.0.1:15433:3306"));
        assertTrue(cmd.contains("lola_relate_data_test-mysql:/var/lib/mysql"));
        assertTrue(cmd.contains("512m"));
        assertTrue(cmd.contains("MYSQL_ROOT_PASSWORD=MySqlPass456!"));
        assertTrue(cmd.contains("MYSQL_DATABASE=mysql_db"));
        assertTrue(cmd.contains("MYSQL_USER=root"));
        assertTrue(cmd.contains("MYSQL_PASSWORD=MySqlPass456!"));
        assertEquals("mysql:8.0", cmd.get(cmd.size() - 1));
    }

    @Test
    void testProvisionSqlServerContainerSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        DatabaseInstance instance = DatabaseInstance.builder()
                .id(id)
                .name("test-mssql")
                .engine(EngineType.SQLSERVER)
                .version("2022")
                .internalPort(15434)
                .adminUsername("sa")
                .adminPassword("StrongMssql#2024")
                .defaultDatabase("master")
                .tier(InstanceTier.PRO_2GB)
                .build();

        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(0, "", ""));

        when(commandRunner.run(anyList(), eq(60L)))
                .thenReturn(new CommandResult(0, "9876543210ab\n", ""));

        String containerId = provisionerService.provisionContainer(instance);
        assertEquals("9876543210ab", containerId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandRunner).run(captor.capture(), eq(60L));
        List<String> cmd = captor.getValue();

        assertTrue(cmd.contains("lola-relate-db-test-mssql"));
        assertTrue(cmd.contains("127.0.0.1:15434:1433"));
        assertTrue(cmd.contains("lola_relate_data_test-mssql:/var/opt/mssql"));
        assertTrue(cmd.contains("2048m"));
        assertTrue(cmd.contains("ACCEPT_EULA=Y"));
        assertTrue(cmd.contains("MSSQL_SA_PASSWORD=StrongMssql#2024"));
        assertEquals("mcr.microsoft.com/mssql/server:2022-latest", cmd.get(cmd.size() - 1));
    }

    @Test
    void testProvisionFallbackWhenDockerUnavailableAndNotStrict() throws Exception {
        DatabaseInstance instance = DatabaseInstance.builder()
                .id(UUID.randomUUID())
                .name("offline-pg")
                .engine(EngineType.POSTGRESQL)
                .build();

        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(1, "", "docker daemon not running"));

        String containerId = provisionerService.provisionContainer(instance);

        assertNotNull(containerId);
        assertTrue(containerId.startsWith("sim-"));
        verify(commandRunner, never()).run(anyList(), eq(60L));
    }

    @Test
    void testProvisionStrictThrowsWhenDockerUnavailable() throws Exception {
        DockerProvisionerService strictService = new DockerProvisionerService(true, true, 60, commandRunner);

        DatabaseInstance instance = DatabaseInstance.builder()
                .id(UUID.randomUUID())
                .name("strict-pg")
                .engine(EngineType.POSTGRESQL)
                .build();

        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(1, "", "docker daemon not running"));

        assertThrows(DockerProvisionerException.class, () -> strictService.provisionContainer(instance));
    }

    @Test
    void testProvisionStrictThrowsOnCommandFailure() throws Exception {
        DockerProvisionerService strictService = new DockerProvisionerService(true, true, 60, commandRunner);

        DatabaseInstance instance = DatabaseInstance.builder()
                .id(UUID.randomUUID())
                .name("strict-failure")
                .engine(EngineType.POSTGRESQL)
                .build();

        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandRunner.run(anyList(), eq(60L)))
                .thenReturn(new CommandResult(125, "", "port already allocated"));

        assertThrows(DockerProvisionerException.class, () -> strictService.provisionContainer(instance));
    }

    @Test
    void testStartContainerSuccess() throws Exception {
        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandRunner.run(List.of("docker", "start", "c-12345"), 60L))
                .thenReturn(new CommandResult(0, "c-12345\n", ""));

        provisionerService.startContainer("c-12345");

        verify(commandRunner).run(List.of("docker", "start", "c-12345"), 60L);
    }

    @Test
    void testStartSimulatedContainerSkipsDocker() throws Exception {
        provisionerService.startContainer("sim-12345-uuid");

        verify(commandRunner, never()).run(List.of("docker", "start", "sim-12345-uuid"), 60L);
    }

    @Test
    void testStopContainerSuccess() throws Exception {
        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandRunner.run(List.of("docker", "stop", "c-12345"), 60L))
                .thenReturn(new CommandResult(0, "c-12345\n", ""));

        provisionerService.stopContainer("c-12345");

        verify(commandRunner).run(List.of("docker", "stop", "c-12345"), 60L);
    }

    @Test
    void testRebootContainerSuccess() throws Exception {
        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandRunner.run(List.of("docker", "restart", "c-12345"), 60L))
                .thenReturn(new CommandResult(0, "c-12345\n", ""));

        provisionerService.rebootContainer("c-12345");

        verify(commandRunner).run(List.of("docker", "restart", "c-12345"), 60L);
    }

    @Test
    void testDeleteContainerSuccess() throws Exception {
        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandRunner.run(List.of("docker", "stop", "c-12345"), 10L))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandRunner.run(List.of("docker", "rm", "-f", "c-12345"), 60L))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandRunner.run(List.of("docker", "volume", "rm", "lola_relate_data_my-db"), 60L))
                .thenReturn(new CommandResult(0, "", ""));

        provisionerService.deleteContainer("c-12345", "my-db");

        verify(commandRunner).run(List.of("docker", "stop", "c-12345"), 10L);
        verify(commandRunner).run(List.of("docker", "rm", "-f", "c-12345"), 60L);
        verify(commandRunner).run(List.of("docker", "volume", "rm", "lola_relate_data_my-db"), 60L);
    }

    @Test
    void testDeleteSimulatedContainerSkipsDocker() throws Exception {
        provisionerService.deleteContainer("sim-abcdef", "my-db");

        verify(commandRunner, never()).run(anyList(), anyLong());
    }

    @Test
    void testGetContainerStatusRunning() throws Exception {
        when(commandRunner.run(List.of("docker", "ps"), 5))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandRunner.run(List.of("docker", "inspect", "--format", "{{.State.Status}}", "c-12345"), 10L))
                .thenReturn(new CommandResult(0, "running\n", ""));

        String status = provisionerService.getContainerStatus("c-12345");
        assertEquals("running", status);
    }

    @Test
    void testGetContainerStatusSimulated() {
        String status = provisionerService.getContainerStatus("sim-12345");
        assertEquals("running", status);
    }
}
