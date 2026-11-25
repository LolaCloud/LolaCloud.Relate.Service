<p align="center">
  <img src="./docs/assets/logo.png" alt="Lola Cloud Logo" width="150" />
</p>

<h1 align="center">Lola Cloud • Relate</h1>

<p align="center">
  <strong>Plataforma Gerenciada de Bancos de Dados Relacionais - DBaaS (Equivalente ao AWS RDS)</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 25" />
  <img src="https://img.shields.io/badge/Spring_Boot-4.1.1-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 4.1.1" />
  <img src="https://img.shields.io/badge/Traefik-v3_TCP_SNI-24A1C1?style=flat-square&logo=traefikproxy&logoColor=white" alt="Traefik v3" />
  <img src="https://img.shields.io/badge/Docker_Engine-API-2496ED?style=flat-square&logo=docker&logoColor=white" alt="Docker Engine" />
  <img src="https://img.shields.io/badge/AWS_S3_SDK-v2-FF9900?style=flat-square&logo=amazons3&logoColor=white" alt="AWS S3" />
  <img src="https://img.shields.io/badge/Port-8084-blue?style=flat-square" alt="Port 8084" />
</p>

---

## 📌 Visão Geral

O **Lola Relate** é o serviço de **Database-as-a-Service (DBaaS)** da plataforma **Lola Cloud**. Ele automatiza o ciclo de vida completo de instâncias de bancos de dados relacionais multi-engine (PostgreSQL, MySQL, SQL Server), gerenciando contêineres Docker isolados, roteamento dinâmico TCP Layer 4 com terminação/inspeção TLS HostSNI via **Traefik v3**, sincronização de DNS no **Runway** e rotinas de backup e restauração integradas ao **Lola Store** (S3).

---

## 🗄️ Principais Responsabilidades

1. **DBaaS Multi-Engine**:
   - **PostgreSQL** (versões 17, 16)
   - **MySQL** (versão 8.0)
   - **SQL Server** (versão 2022)
2. **Provisionamento Docker & Isolamento de Segurança**:
   - Criação de contêineres dedicados (`lola-relate-db-<nome>`) com volumes persistentes.
   - Limites de memória e CPU por Tier (`MICRO_512MB`, `STANDARD_1GB`, `PRO_2GB`).
   - **Binding exclusivo em Loopback**: As instâncias escutam apenas em `127.0.0.1:<internalPort>`, nunca em interfaces abertas.
3. **Roteamento Dinâmico no Traefik (TCP Layer 4 TLS HostSNI)**:
   - Geração automática de configurações dinâmicas YAML em `relate/traefik/dynamic/db-<nome>.yaml`.
   - Permite que centenas de instâncias compartilhem as portas padrão oficiais (`5432`, `3306`, `1433`) diferenciadas pelo hostname SNI.
4. **Integração com Lola Runway & DNS**:
   - Criação automática de registros DNS tipo **A** e configuração de DDNS no momento do provisionamento.
5. **Backups e Snapshots em S3**:
   - Dumps automatizados (`pg_dump`, `mysqldump`, `sqlcmd BACKUP`) enviados diretamente para o bucket privado `lola-relate-backups` do Lola Store com restauração com 1-clique.

---

## 📡 Principais Endpoints da API REST

| Método | Endpoint | Descrição |
| :--- | :--- | :--- |
| `GET` | `/api/v1/relate/instances` | Lista instâncias provisionadas |
| `POST` | `/api/v1/relate/instances` | Provisiona nova instância de banco de dados |
| `GET` | `/api/v1/relate/instances/{id}` | Detalhes, credenciais e status da instância |
| `POST` | `/api/v1/relate/instances/{id}/start` | Inicia contêiner parado |
| `POST` | `/api/v1/relate/instances/{id}/stop` | Para contêiner em execução |
| `POST` | `/api/v1/relate/instances/{id}/reboot` | Reinicia contêiner |
| `DELETE` | `/api/v1/relate/instances/{id}` | Destrói contêiner, volumes, rotas e DNS |
| `GET` | `/api/v1/relate/backups` | Lista backups e snapshots existentes |
| `POST` | `/api/v1/relate/backups` | Executa novo snapshot sob demanda no S3 |
| `POST` | `/api/v1/relate/backups/{id}/restore` | Restaura snapshot para a instância |
| `GET` | `/api/v1/relate/backups/{id}/download-url` | Gera URL pré-assinada de download do dump |

---

## 🛠️ Stack Tecnológica

- **Linguagem**: Java 25 (OpenJDK)
- **Framework**: Spring Boot 4.1.1
- **Roteamento de Borda**: Traefik v3.1 (TCP HostSNI)
- **Orquestração**: Docker CLI & Unix Socket via Java `ProcessBuilder`
- **Armazenamento de Snapshots**: AWS SDK for Java v2 (`software.amazon.awssdk:s3:2.29.38`)
- **Banco de Dados**: PostgreSQL 17 (`jdbc:postgresql://localhost:5433/lola_relate`)

---

## 🚀 Como Executar

```bash
# Executar a aplicação
./mvnw spring-boot:run
```
Porta padrão: **`8084`**
