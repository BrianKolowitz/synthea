package org.mitre.synthea.export;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import org.mitre.synthea.engine.Generator;
import org.mitre.synthea.helpers.SimpleCSV;
import org.mitre.synthea.helpers.Utilities;

public class PrevalenceReport {

  // These are the name of the columns in the CSV prevalence template
  private static final String DIFFERENCE = "DIFFERENCE";
  private static final String ACTUAL_PREV_PERCENT = "ACTUAL PREVALENCE PERCENT";
  private static final String PREV_RATE = "SYNTHEA PREVALENCE RATE";
  private static final String PREV_PERCENT = "SYNTHEA PREVALENCE PERCENT";
  private static final String CODE = "CODE";
  private static final String DISPLAY = "DISPLAY";
  private static final String GENDER = "GENDER";
  private static final String RACE = "RACE";
  private static final String AGE = "AGE GROUP";
  private static final String OCCURRENCES = "SYNTHEA OCCURRENCES";
  private static final String POPULATION = "SYNTHEA POPULATION";
  private static final String GIVEN_CON_DISPLAY = "GIVEN CONDITION DISPLAY";
  private static final String GIVEN_CON_CODE = "GIVEN CONDITION CODE";
  
  private static final String ALL = "*";

  public static void export(Generator generator) throws Exception {
    if (generator.database == null) {
      System.err.println(
          "Unable to generate Prevalence Report - No database exists to generate report from.");
      return;
    }

    String csvData = Utilities.readResource("prevalence_template.csv");
    List<LinkedHashMap<String, String>> data = SimpleCSV.parse(csvData);

    try (Connection connection = generator.database.getConnection()) {

      for (LinkedHashMap<String, String> line : data) {

        if (line.get(CODE).isEmpty() && line.get(DISPLAY).isEmpty()) {
          continue;
        }

        getPrevalence(connection, line);
        completeSyntheaFields(line);
        calculateDifferenceFromActual(line);
      }

      allConditions(connection, data);
    }

    String newCsvData = SimpleCSV.unparse(data);

    File outDirectory = Exporter.getOutputFolder("prevalence", null);

    Path outFilePath = outDirectory.toPath()
        .resolve("prev_data" + System.currentTimeMillis() + ".csv");

    Files.write(outFilePath, Collections.singleton(newCsvData), StandardOpenOption.CREATE_NEW);
  }

  /**
   * Constructs and executes a query to find the population and occurrences 
   * for the condition and filters on a single line of the report.
   * 
   * @param connection Database connection
   * @param line Current line of the prevalence report to look up stats for and populate fields
   */
  private static void getPrevalence(Connection connection, LinkedHashMap<String, String> line)
      throws SQLException {

    StringBuilder sb = new StringBuilder();
    sb.append("SELECT COUNT(*) population, \n"); // main query selects entire population
    sb.append("SUM( CASE WHEN c.CODE is NOT NULL then 1 ELSE 0 END ) occurrences \n");
    // subquery to count rows where the named condition exists, using an outer join
    sb.append("FROM PERSON p \n");
    sb.append("LEFT JOIN CONDITION c on p.id = c.PERSON_ID and (c.CODE = ? OR c.DISPLAY = ?) \n");

    String age = line.get(AGE);
    if (!age.equals(ALL)) {
      sb.append("LEFT JOIN ATTRIBUTE a on p.ID = a.PERSON_ID and a.NAME= 'AGE'  \n");
    }

    sb.append("WHERE p.DATE_OF_DEATH is null \n"); 

    String gender = line.get(GENDER);
    if (!gender.equals(ALL)) {
      sb.append("AND (p.GENDER = ?)\n");
    }

    String race = line.get(RACE);
    if (!race.equals(ALL)) {
      sb.append("AND (p.RACE = ?)\n");
    }

    if (age.equals("adult")) {
      sb.append("AND CAST(a.VALUE AS INT) >= 18  \n");
    } else if (age.equals("child")) {
      sb.append("AND CAST(a.VALUE AS INT) < 18  \n");
    } else if (age.equals("senior")) {
      sb.append("AND CAST(a.VALUE AS INT) >= 65  \n");
    }

    String givenConditionDisplay = line.get(GIVEN_CON_DISPLAY);
    String givenConditionCode = line.get(GIVEN_CON_CODE);
    if (!givenConditionDisplay.isEmpty() || !givenConditionCode.isEmpty()) {
      sb.append("AND EXISTS(")
        .append("  SELECT 1 FROM CONDITION gc")
        .append("  WHERE p.ID = gc.PERSON_ID")
        .append("  AND (gc.CODE = ? OR gc.DISPLAY = ?)")
        .append(") \n");
    }

    PreparedStatement stmt = connection.prepareStatement(sb.toString());

    int index = 1; // SQL begins at 1 not 0
    stmt.setString(index++, line.get(CODE));
    stmt.setString(index++, line.get(DISPLAY));

    if (!gender.equals(ALL)) {
      stmt.setString(index++, gender);
    }

    if (!race.equals(ALL)) {
      stmt.setString(index++, race);
    }

    if (!givenConditionDisplay.isEmpty() || !givenConditionCode.isEmpty()) {
    	stmt.setString(index++, givenConditionCode);
    	stmt.setString(index++, givenConditionDisplay);
    }

    ResultSet rs = stmt.executeQuery();

    rs.next();

    int population = rs.getInt(1);
    int occurrences = rs.getInt(2);
    line.put(POPULATION, Integer.toString(population));
    line.put(OCCURRENCES, Integer.toString(occurrences));
  }

  /**
   * Calculates the prevalence rate and percent based on what is on that line of the report. Inserts
   * result of calculation into the prevalence rate and percent columns.
   */
  private static void completeSyntheaFields(LinkedHashMap<String, String> line) {
    if (line.get(OCCURRENCES).isEmpty() || line.get(POPULATION).isEmpty()) {
      line.put(PREV_RATE, (null));
      line.put(PREV_PERCENT, (null));
    } else {
      double occurr = Double.parseDouble(line.get(OCCURRENCES));
      double pop = Double.parseDouble(line.get(POPULATION));

      if (pop != 0) {
        double prevRate = occurr / pop;
        double prevPercent = prevRate * 100;
        line.put(PREV_RATE, Double.toString(prevRate));
        line.put(PREV_PERCENT, Double.toString(prevPercent));
      } else {
        line.put(PREV_RATE, Double.toString(0));
        line.put(PREV_PERCENT, Double.toString(0));
      }
    }
  }

  /**
   * Calculates the difference between the Synthea prevalence percent and actual percent based on
   * what is on that line of the report. Inserts result of calculation into the difference column.
   */
  private static void calculateDifferenceFromActual(LinkedHashMap<String, String> line) {
    if (line.get(ACTUAL_PREV_PERCENT).isEmpty()) {
      line.put(DIFFERENCE, (null));
    } else {
      double actualPrev = Double.parseDouble(line.get(ACTUAL_PREV_PERCENT));
      double prevPercent = Double.parseDouble(line.get(PREV_PERCENT));
      double diff = (prevPercent - actualPrev);
      line.put(DIFFERENCE, Double.toString(diff));
    }
  }

  /**
   * Calculates the unique number of patients who have a distinct disease. Inserts result of query
   * into the occurrences column. Calculates the total living population of patients. Inserts result
   * into the population column. Calls for completeSyntheaFields to calculate the prevalence rate
   * and percent.
   */
  private static void allConditions(Connection connection, List<LinkedHashMap<String, String>> data)
      throws SQLException {

    PreparedStatement stmt = connection
        .prepareStatement("select count(*) from person where person.DATE_OF_DEATH is null");
    ResultSet rs = stmt.executeQuery();
    rs.next();
    int totalPopulation = rs.getInt(1);

    stmt = connection.prepareStatement(
        "select distinct c.display as DistinctDisplay, "
        + "c.code DistinctCode, "
        + "count(distinct c.person_id) as CountDisplay \n"
        + "from condition c, person p\n" 
        + "where c.person_id = p.id\n"
        + "and p.date_of_death is null\n" 
        + "group by c.display, c.code \n" 
        + "order by c.display ASC");
    rs = stmt.executeQuery();
    while (rs.next()) {
      String disease = rs.getString("DistinctDisplay");
      String code = rs.getString("DistinctCode");
      int count = rs.getInt("CountDisplay");
      LinkedHashMap<String, String> line = new LinkedHashMap<String, String>();
      line.put(CODE, code);
      line.put(DISPLAY, disease);
      line.put(OCCURRENCES, Integer.toString(count));
      line.put(POPULATION, Integer.toString(totalPopulation));
      data.add(line);
      completeSyntheaFields(line);
    }
  }
}
