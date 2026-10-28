package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.cursus.jdbc.JdbcDialect;
import dev.appiaworks.shiguchi.cursus.jdbc.JdbcPushStore;
import java.time.Duration;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

@DisplayName("Push JDBC engine wiring")
class PushJdbcConfigurationTest {

    private static DataSource h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:push_config_" + name + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DataSource> provider(DataSource unique) {
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(unique);
        return provider;
    }

    private static PushProperties properties(String table) {
        PushProperties properties = new PushProperties();
        properties.setStore(PushProperties.Engine.JDBC);
        properties.getJdbc().setTable(table);
        properties.getJdbc().setSweepInterval(Duration.ZERO);
        return properties;
    }

    @Test
    @DisplayName("GIVEN store JDBC and a single DataSource WHEN the store bean is built SHOULD use that DataSource")
    void given_storeJdbcAndSingleDataSource_when_storeBuilt_should_useIt() {
        PushProperties properties = properties("push_event_config_single");
        BeanFactoryHolder beans = new BeanFactoryHolder();

        JdbcPushStore store =
                new PushConfiguration.Jdbc().pushJdbcStore(properties, provider(h2("single")), beans.factory);
        store.afterPropertiesSet();

        assertThat(store.dialect()).isEqualTo(JdbcDialect.H2);
        assertThat(store.consumerGroups()).isTrue();
        assertThat(store.deliveryLog()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a named data-source WHEN the store bean is built SHOULD take that bean, not the unique one")
    void given_namedDataSource_when_storeBuilt_should_takeNamedBean() {
        PushProperties properties = properties("push_event_config_named");
        properties.getJdbc().setDataSource("pushDb");
        BeanFactoryHolder beans = new BeanFactoryHolder();
        DataSource named = h2("named");
        beans.factory.registerSingleton("pushDb", named);

        JdbcPushStore store = new PushConfiguration.Jdbc().pushJdbcStore(properties, provider(null), beans.factory);
        store.afterPropertiesSet();

        assertThat(store.historyLength(Topic.parse("orders:1"))).isZero();
    }

    @Test
    @DisplayName("GIVEN a data-source name with no such bean WHEN the store bean is built SHOULD throw naming it")
    void given_unknownDataSourceName_when_storeBuilt_should_throw() {
        PushProperties properties = properties("push_event_config_unknown");
        properties.getJdbc().setDataSource("nope");

        assertThatThrownBy(() -> new PushConfiguration.Jdbc()
                        .pushJdbcStore(properties, provider(h2("unknown")), new BeanFactoryHolder().factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cursus.jdbc.data-source")
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("GIVEN no unique DataSource WHEN the store bean is built SHOULD throw telling how to configure one")
    void given_noUniqueDataSource_when_storeBuilt_should_throw() {
        PushProperties properties = properties("push_event_config_none");

        assertThatThrownBy(() -> new PushConfiguration.Jdbc()
                        .pushJdbcStore(properties, provider(null), new BeanFactoryHolder().factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DataSource")
                .hasMessageContaining("cursus.jdbc.data-source");
    }

    @Test
    @DisplayName(
            "GIVEN retention above zero on the JDBC store WHEN the archive bean is built SHOULD be the store itself")
    void given_retentionOnJdbcStore_when_archiveBuilt_should_beTheStore() {
        PushProperties properties = properties("push_event_config_archive");
        properties.getArchive().setRetention(Duration.ofHours(1));
        JdbcPushStore store = new JdbcPushStore(h2("archive"), properties.getJdbc(), properties.getArchive());

        PushArchive archive = new PushConfiguration().pushArchive(properties, store);

        assertThat(archive).isSameAs(store);
        assertThat(archive.searchCapabilities().enabled()).isTrue();
    }

    @Test
    @DisplayName("GIVEN broker JDBC and store JDBC WHEN the broker bean is built SHOULD be the polling JDBC broker")
    void given_brokerJdbc_when_brokerBuilt_should_bePollingBroker() {
        PushProperties properties = properties("push_event_config_broker");
        properties.setBroker(PushProperties.Engine.JDBC);

        PushBroker broker = new PushConfiguration.Jdbc()
                .pushJdbcBroker(
                        properties,
                        provider(h2("broker")),
                        new BeanFactoryHolder().factory,
                        emptyStores(),
                        emptyRegistries());

        assertThat(broker).isInstanceOf(dev.appiaworks.shiguchi.cursus.jdbc.JdbcPushBroker.class);
    }

    @Test
    @DisplayName(
            "GIVEN broker JDBC and a store that is not JDBC WHEN the broker bean is built SHOULD say the store must be JDBC")
    void given_brokerJdbcWithOtherStore_when_brokerBuilt_should_throw() {
        PushProperties properties = properties("push_event_config_broker_other");
        properties.setStore(PushProperties.Engine.MEMORY);
        properties.setBroker(PushProperties.Engine.JDBC);

        assertThatThrownBy(() -> new PushConfiguration.Jdbc()
                        .pushJdbcBroker(
                                properties,
                                provider(h2("broker_other")),
                                new BeanFactoryHolder().factory,
                                emptyStores(),
                                emptyRegistries()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("store to JDBC");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JdbcPushStore> emptyStores() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> emptyRegistries() {
        return mock(ObjectProvider.class);
    }

    @Test
    @DisplayName(
            "GIVEN a table name that is not a plain identifier WHEN the store is built SHOULD throw naming the property")
    void given_badTableName_when_storeBuilt_should_throw() {
        PushProperties properties = properties("push-event; drop table x");

        assertThatThrownBy(() -> new JdbcPushStore(h2("badtable"), properties.getJdbc()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cursus.jdbc.table");
    }

    private static final class BeanFactoryHolder {
        final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
    }
}
