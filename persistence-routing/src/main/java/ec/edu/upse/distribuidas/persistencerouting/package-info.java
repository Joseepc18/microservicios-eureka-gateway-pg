/**
 * Enrutamiento de lecturas y escrituras hacia PostgreSQL.
 *
 * <p>Las escrituras se dirigen al nodo primario y las lecturas ordinarias se reparten entre las
 * réplicas. La implementación se agrega en las issues de persistencia.
 */
package ec.edu.upse.distribuidas.persistencerouting;
