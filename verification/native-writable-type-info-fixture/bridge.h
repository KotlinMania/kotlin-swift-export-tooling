#include <stdbool.h>
void *fixture_create_list(void);
void *fixture_export_array(void *handle);
bool fixture_check_identity(void *handle, void *pointer);
bool fixture_check_mutation(void *handle);
void fixture_append_kotlin(void *handle);
void fixture_dispose_list(void *handle);
