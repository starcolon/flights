package starcolon.flights.core

/**
 * Core module of Flights
 */
object Core extends App {
  import starcolon.flights.openflights._
  import starcolon.flights.routemap._
  import starcolon.flights.geo._
  import scala.io.StdIn.{readLine, readInt}

  // Prompt the user for inputs
  println("Let's find best routes!")
  val citySource = readLine(Console.CYAN + "Source city: " + Console.RESET)
  val cityDest   = readLine(Console.CYAN + "Destination city: " + Console.RESET)
  print(Console.CYAN + "Max connections: " + Console.RESET)
  var maxDegree  = readInt()

  println(citySource + " ✈ ️ " + cityDest)

  // Find direct flights between two cities
  val routesDirect = RouteMap.findCityRoutes(citySource, cityDest)
  println(Console.MAGENTA + "[Direct flights]" + Console.RESET)
  routesDirect foreach { _.prettyPrint() }

  // Find indirect flights between two cities
  val routesIndirect = RouteMap.findCityIndirectRoutes(citySource, cityDest, maxDegree)
  println(Console.MAGENTA + "[Indirect flights]" + Console.RESET)
  routesIndirect foreach { _.prettyPrint() }
}
