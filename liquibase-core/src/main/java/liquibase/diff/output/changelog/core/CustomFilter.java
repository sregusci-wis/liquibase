package liquibase.diff.output.changelog.core;

public class CustomFilter {
    public CustomFilter(String filterCondition) {
        this.filterCondition = filterCondition;
    }

    public String getFilterCondition() {
        return filterCondition;
    }


    //TODO: AGREGAR SANITIZE DE SQL y PARA QUE NO HAYA UN INJECT DE SQL, SI HACEN DELETE O ALGUNA LOCURA

    String filterCondition;
}
