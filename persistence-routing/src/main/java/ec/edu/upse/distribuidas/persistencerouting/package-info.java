/**
 * Enrutamiento de lecturas y escrituras hacia PostgreSQL.
 *
 * <p>Las escrituras se dirigen al nodo primario y las lecturas de solo lectura se reparten entre
 * las réplicas con una política round-robin. La entrada es
 * {@link ec.edu.upse.distribuidas.persistencerouting.PersistenceRoutingAutoConfiguration}; el uso
 * y las propiedades están en el {@code README.md} del módulo.
 */
package ec.edu.upse.distribuidas.persistencerouting;
