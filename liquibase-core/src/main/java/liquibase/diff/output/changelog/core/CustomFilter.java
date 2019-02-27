package liquibase.diff.output.changelog.core;

public class CustomFilter {
    public CustomFilter(String filterCondition,String rowCount,String rowIndex, String orderColumn ) {
        this.filterCondition = filterCondition;

        /*
        if (rowCount< 0)
            throw new Exception("RowCount must be 0 or larger");

        if (rowIndex < 0)
            throw new Exception("RowIndex must be 0 or larger");
            */

        this.rowCount = rowCount;
        this.rowIndex = rowIndex;
        this.orderColumn = orderColumn;
    }

    //TODO: AGREGAR SANITIZE DE SQL y PARA QUE NO HAYA UN INJECT DE SQL, SI HACEN DELETE O ALGUNA LOCURA

    public String getFilterCondition() {
        return filterCondition;
    }

    public String getRowCount() {
        return rowCount;
    }

    public String getRowIndex() {
        return rowIndex;
    }

    public String getOrderColumn() {
        return orderColumn;
    }

    String filterCondition;
    String rowCount;
    String rowIndex;
    String orderColumn;
}
