package flights

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.math.Ordered.orderingToOrdered
import scala.concurrent.Future

/**
 * Link between two airports and its operating airlines
 */
case class AirportLink(sourceAirport: Airport, destAirport: Airport, airlines: List[String]) {
  val distance: Float =
    Geo.distance(sourceAirport.lat, sourceAirport.lng, destAirport.lat, destAirport.lng)

  def prettyPrint(prefix: String) {
    val airlines_ = airlines.mkString(",")
    println(
      prefix +
        sourceAirport.city + " (" + sourceAirport.code + ") → " +
        destAirport.city + " (" + destAirport.code + ")" +
        Console.GREEN + " ✈ via: " +
        Console.RESET + airlines_
    )
  }
}

case class ConnectedRoutes(routes: Seq[AirportLink]) {

  /**
   * Concatenate two connected routes
   */
  def ++(that: ConnectedRoutes): ConnectedRoutes = {
    ConnectedRoutes(routes ++ that.routes)
  }

  /**
   * Prepend an airport link to the underlying connected routes
   * @return {ConnectedRoutes} resultant connected routes
   */
  def prependLink(that: AirportLink): ConnectedRoutes = {
    ConnectedRoutes(that +: routes)
  }

  /**
   * The aggregate travelling distance of all the connected routes, in meters.
   */
  def totalDistance: Float =
    if (routes.isEmpty) 0F
    else routes.foldLeft(0F) { (total, route) => total + route.distance }

  /**
   * The direct geographical distance from the origin airport to the final
   * airport of the connected routes, in meters.
   */
  def displacement: Float =
    if (routes.length < 1)
      0F
    else if (routes.length == 1) { // A single hop
      Geo.distance(
        routes.head.sourceAirport.lat,
        routes.head.sourceAirport.lng,
        routes.head.destAirport.lat,
        routes.head.destAirport.lng
      )
    } else { // Connected hops
      val origin = routes.head.sourceAirport
      val destination = routes.last.destAirport
      Geo.distance(origin.lat, origin.lng, destination.lat, destination.lng)
    }

  def prettyPrint() {
    if (routes.length > 0) {
      val chainedSources = routes.map(r =>
        Console.YELLOW +
          r.sourceAirport.city + " (" + r.sourceAirport.code + ")" +
          Console.RESET).mkString(" → ")

      println(
        Console.CYAN + s"[${routes.length} hops] " +
          chainedSources + " → " +
          Console.YELLOW + routes.last.destAirport.city + " (" + routes.last.destAirport.code + ")" +
          Console.RESET
      )
      routes.foreach { _.prettyPrint("   ") }
    }
  }
}

/**
 * Route mapper/finder utility
 */
object RouteMap {

  /**
   * Find all direct routes between two cities
   */
  def findCityRoutes(citySrc: String, cityDest: String): Future[Seq[Route]] = {
    val airports = findSourceAndDestinationAirports(citySrc, cityDest)

    // Expand all routes which connect from
    // the source airport to any of the destination airports
    // OPTIMIZE: ought to be possible to reduce to one query, `WHERE src IN (...) AND dst IN (...)`

    // format: OFF - ugh: https://github.com/scala-ide/scalariform/issues/29
    airports flatMap { case (sources, dests) =>
      val routes = for {
        src <- sources
        dst <- dests
      } yield OpenFlightsDB.findAirportRoutes(src.code, dst.code)

      Future.reduce(routes)(_ ++ _)
    }
    // format: ON
  }

  /**
   * Find all routes which connect from the specified airport
   * and end at any of the given list of the destination airports
   */
  def findAirportRoutes(srcAirport: Airport, dstAirports: Seq[Airport]): Future[Seq[Route]] = {
    // OPTIMIZE: we could produce a `WHERE dst IN (...)` query instead of N queries.
    val routes = dstAirports map { dstAirport =>
      OpenFlightsDB.findAirportRoutes(srcAirport.code, dstAirport.code)
    }
    Future.reduce(routes)(_ ++ _)
  }

  /**
   * Find all chained routes which connect two cities
   */
  def findCityIndirectRoutes(citySrc: String, cityDest: String, maxConnection: Int): Future[Seq[ConnectedRoutes]] = {
    val airports = findSourceAndDestinationAirports(citySrc, cityDest)

    // Expand all connected routes recursively
    // format: OFF - ugh: https://github.com/scala-ide/scalariform/issues/29
    airports flatMap { case (sources, dests) =>
      // Calculate a sample straight distance from the source to the destination
      val straightDistance = findDistance(sources.head, dests.head) // TODO: should use headOption

      val routes = sources map { srcAirport =>
        findIndirectRoutesFromAirport(
          srcAirport,
          cityDest,
          maxConnection,
          straightDistance
        )
      }

      Future.reduce(routes)(_ ++ _)
    }
    // format: ON
  }

  /**
   * Calculate the distance between the source and the destination airport
   * in metre
   */
  def findDistance(srcAirport: Airport, dstAirport: Airport): Float = {
    Geo.distance(srcAirport.lat, srcAirport.lng, dstAirport.lat, dstAirport.lng)
  }

  /**
   * Find all connected routes which start at the specified airport
   * and end at the specified city
   * where each of them should not be longer than the given
   * number of connections
   * @param {Airport} The airport to start from
   * @param {String} The final destination city we're looking for
   * @param {Set[String]} The list of cities want to skip (usuall cities we departed)
   * @param {Int} Maximum number of connections
   * @param {Float} Straight distance from the source city to the destination city (in metre)
   */
  def findIndirectRoutesFromAirport(srcAirport: Airport, cityFinalDest: String, maxConnection: Int, straightDistance: Float, skipCities: Set[String] = Set.empty[String]): Future[Seq[ConnectedRoutes]] = {

    if (maxConnection <= 0)
      Future.successful(Nil)
    else {
      // Expand all routes which start at the specified airport
      val departures = OpenFlightsDB.findDepartureRoutes(srcAirport.code)

      departures flatMap { departures =>
        // Group all departure routes by destination airport
        val byDestination = departures.groupBy(_.airportDestCode)

        // format: OFF - ugh: https://github.com/scala-ide/scalariform/issues/29
        val routes = byDestination map { case (destAirportCode, routes) =>
          expandRoutes(
            srcAirport,
            destAirportCode,
            cityFinalDest,
            skipCities,
            routes,
            maxConnection,
            straightDistance
          )
        }
        // format: ON

        if (routes.isEmpty)
          Future.successful(Nil)
        else
          Future.reduce(routes)(_ ++ _)
      }
    }
  }

  private def findSourceAndDestinationAirports(srcCity: String, dstCity: String): Future[(Seq[Airport], Seq[Airport])] =
    OpenFlightsDB.findAirports(srcCity) zip OpenFlightsDB.findAirports(dstCity)

  /**
   * Expand further routes beginning from the specified airport
   */
  private def expandRoutes(srcAirport: Airport, destAirportCode: String, cityFinalDest: String, skipCities: Set[String], routes: Seq[Route], maxConnection: Int, straightDistance: Float): Future[Seq[ConnectedRoutes]] = {
    // Treat this as a single common route
    // operated by multiple airlines
    val airlines = routes.map(_.airlineCode).toList
    val destAirports = OpenFlightsDB.findAirportByCode(destAirportCode)

    destAirports flatMap { destAirports =>
      if (destAirports.isEmpty) Future.successful(Nil) // Unable to locate the destination airport, skip it
      else {
        val destAirport = destAirports.head
        val link = AirportLink(srcAirport, destAirport, airlines)

        // The expansion ends if all these routes 
        // end up at the final destination city
        if (destAirport.city == cityFinalDest) Future.successful(List(ConnectedRoutes(Seq(link))))

        // Skip the city we don't want to land at
        else if (skipCities contains destAirport.city) Future.successful(Nil)

        // Skip if the distance of the link exceeds total straight distance
        // (Some margin is added for relaxation)
        else if (link.distance * 1.1 > straightDistance) Future.successful(Nil)

        else {
          // Expand further routes from the current landed airport
          val nextRoutes = findIndirectRoutesFromAirport(
            destAirport,
            cityFinalDest,
            maxConnection - 1,
            straightDistance,
            skipCities + srcAirport.city
          )

          // Assemble all the expanded links
          nextRoutes map { routes =>
            routes.map(_.prependLink(link))
          }
        }
      }
    }
  }

  /**
   * Find the city where the given route arrives
   */
  def getDestCityOfRoute(route: Route): Future[String] = {
    val cities = OpenFlightsDB.findCitiesConnectedByRoute(route)
    cities map { case (_, dest) => dest }
  }
}
