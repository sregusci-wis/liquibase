package liquibase.diff.output.changelog.core;

import liquibase.change.Change;
import liquibase.change.ColumnConfig;
import liquibase.change.core.InsertDataChange;
import liquibase.configuration.GlobalConfiguration;
import liquibase.configuration.LiquibaseConfiguration;
import liquibase.database.Database;
import liquibase.database.core.InformixDatabase;
import liquibase.database.core.MSSQLDatabase;
import liquibase.database.core.OracleDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.AbstractChangeGenerator;
import liquibase.diff.output.changelog.ChangeGeneratorChain;
import liquibase.diff.output.changelog.MissingObjectChangeGenerator;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.statement.DatabaseFunction;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.*;
import liquibase.util.JdbcUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class MissingDataChangeGenerator extends AbstractChangeGenerator implements MissingObjectChangeGenerator {

    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        if (Data.class.isAssignableFrom(objectType)) {
            return PRIORITY_DEFAULT;
        }
        return PRIORITY_NONE;
    }

    @Override
    public Class<? extends DatabaseObject>[] runAfterTypes() {
        return new Class[]{
                Table.class
        };
    }

    @Override
    public Class<? extends DatabaseObject>[] runBeforeTypes() {
        return new Class[]{
                PrimaryKey.class, ForeignKey.class, Index.class
        };
    }

    @Override
    public Change[] fixMissing(DatabaseObject missingObject, DiffOutputControl outputControl, Database referenceDatabase, Database comparisionDatabase, ChangeGeneratorChain chain) {
        return getMissing((Data) missingObject, outputControl, referenceDatabase, Optional.empty());
    }

    private Change[] getMissing(Data missingObject, DiffOutputControl outputControl, Database referenceDatabase, Optional<CustomFilter> filter ) {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Data data = missingObject;

            Table table = data.getTable();
            if (referenceDatabase.isLiquibaseObject(table)) {
                return null;
            }
            String sql = "";

            //ver el tipo de BD si es oracle o MSQL
            //ver si estan los index row number
            //si no esta hacer where directos
            if (filter.isPresent()) {
                if ((filter.get().getRowIndex() != null) && (filter.get().getRowCount() != null) && (filter.get().getOrderColumn() != null)){
                    if (referenceDatabase instanceof OracleDatabase) {
                        int maxRows = Integer.parseInt(filter.get().getRowIndex()) + Integer.parseInt(filter.get().getRowCount() ) - 1;
                        sql = String.format("select * from (select a.*, ROWNUM rnum from (select * from %s where %s ORDER BY %s ) a ) where rnum >= %s and rownum <= %s",
                                referenceDatabase.escapeTableName(table.getSchema().getCatalogName(), table.getSchema().getName(), table.getName()),
                                filter.get().getFilterCondition(),
                                filter.get().getOrderColumn(),
                                filter.get().getRowIndex(),
                                maxRows);
                        //"SELECT * FROM " + referenceDatabase.escapeTableName(table.getSchema().getCatalogName(), table.getSchema().getName(), table.getName()) + " WHERE " + filter.get().getFilterCondition();
                        // select * from (select a.*, ROWNUM rnum from (select * from T_FOTO_STOCK where CD_EMPRESA = 10000 ORDER BY DT_FABRICACAO ) a ) where rnum >= 1 and rownum <= 5
                    } else if (referenceDatabase instanceof MSSQLDatabase) {
                        sql = String.format("SELECT * FROM   %s where %s ORDER BY %s asc OFFSET %s ROWS FETCH NEXT %s ROWS ONLY",
                                referenceDatabase.escapeTableName(table.getSchema().getCatalogName(), table.getSchema().getName(), table.getName()),
                                filter.get().getFilterCondition(),
                                filter.get().getOrderColumn(),
                                filter.get().getRowIndex(),
                                filter.get().getRowCount());
                        //SELECT * FROM   T_STOCK_MAESTRO where NU_MAESTRO_STOCK > 2 ORDER BY DT_ADDROW asc OFFSET 10 ROWS FETCH NEXT 10 ROWS ONLY
                    }
                }else {
                    sql = "SELECT * FROM " + referenceDatabase.escapeTableName(table.getSchema().getCatalogName(), table.getSchema().getName(), table.getName()) + " WHERE " + filter.get().getFilterCondition();
                }
            }
            else
                sql = "SELECT * FROM " + referenceDatabase.escapeTableName(table.getSchema().getCatalogName(), table.getSchema().getName(), table.getName());

            stmt = ((JdbcConnection) referenceDatabase.getConnection()).createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            stmt.setFetchSize(1000);
            rs = stmt.executeQuery(sql);

            List<String> columnNames = new ArrayList<>();
            for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                columnNames.add(rs.getMetaData().getColumnName(i + 1));
            }
            //cambiar capacity a el total de registro que hay para guardar
            List<Change> changes = new ArrayList<>(10000000);
            while (rs.next()) {
                InsertDataChange change = new InsertDataChange();
                if (outputControl.getIncludeCatalog()) {
                    change.setCatalogName(table.getSchema().getCatalogName());
                }
                if (outputControl.getIncludeSchema()) {
                    change.setSchemaName(table.getSchema().getName());
                }
                change.setTableName(table.getName());

                // loop over all columns for this row
                for (int i = 0; i < columnNames.size(); i++) {
                    ColumnConfig column = new ColumnConfig();
                    column.setName(columnNames.get(i));

                    Object value = JdbcUtils.getResultSetValue(rs, i + 1);
                    if (value == null) {
                        column.setValue(null);
                    } else if (value instanceof Number) {
                        column.setValueNumeric((Number) value);
                    } else if (value instanceof Boolean) {
                        column.setValueBoolean((Boolean) value);
                    } else if (value instanceof Date) {
                        column.setValueDate((Date) value);
                    } else if (value instanceof byte[]) {
                        if (referenceDatabase instanceof InformixDatabase) {
                            column.setValue(new String((byte[]) value, LiquibaseConfiguration.getInstance().getConfiguration(GlobalConfiguration.class).getOutputEncoding()));
                        }
                        column.setValueComputed(new DatabaseFunction("UNSUPPORTED FOR DIFF: BINARY DATA"));
                    } else { // fall back to simple string
                        column.setValue(value.toString().replace("\\", "\\\\"));
                    }

                    if (!column.getName().toUpperCase().equals("RNUM"))
                        change.addColumn(column);

                }

                // for each row, add a new change
                // (there will be one group per table)
                changes.add(change);
            }

            return changes.toArray(new Change[changes.size()]);
        } catch (Exception e) {
            throw new UnexpectedLiquibaseException(e);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException ignore) {
                }
            }
        }
    }

    @Override
    public Change[] fixMissing(DatabaseObject missingObject, DiffOutputControl outputControl, Database referenceDatabase, Database comparisionDatabase, ChangeGeneratorChain chain, CustomFilter filter) {
        return getMissing((Data) missingObject, outputControl, referenceDatabase,Optional.of(filter));
    }
}
