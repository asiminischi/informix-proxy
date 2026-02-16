"""
Informix gRPC Client for Python

High-performance client that connects to Informix via gRPC proxy

Features:
- Automatic connection management
- Streaming large result sets
- Transaction support
- Prepared statements
- Type hints and modern Python practices
"""

import grpc
from typing import List, Dict, Any, Optional, Callable
import informix_pb2
import informix_pb2_grpc


class InformixClient:
    """Client for Informix database via gRPC proxy"""

    def __init__(self, proxy_host: str = 'localhost', proxy_port: int = 50051):
        """
        Initialize client

        Args:
            proxy_host: gRPC proxy server host
            proxy_port: gRPC proxy server port
        """
        self.channel = grpc.insecure_channel(f'{proxy_host}:{proxy_port}')
        self.stub = informix_pb2_grpc.InformixServiceStub(self.channel)
        self.connection_id: Optional[str] = None

    def connect(
            self,
            host: str,
            port: int,
            database: str,
            username: str,
            password: str,
            properties: Optional[Dict[str, str]] = None,
            pool_size: int = 10
    ) -> Dict[str, str]:
        """
        Connect to Informix database

        Args:
            host: Database server host
            port: Database server port
            database: Database name
            username: Username
            password: Password
            properties: Additional JDBC properties
            pool_size: Connection pool size

        Returns:
            Connection info with connection_id and server_version

        Raises:
            Exception: If connection fails
        """
        request = informix_pb2.ConnectionRequest(
            host=host,
            port=port,
            database=database,
            username=username,
            password=password,
            properties=properties or {},
            pool_size=pool_size
        )

        response = self.stub.Connect(request)

        if not response.success:
            raise Exception(f"Connection failed: {response.error}")

        self.connection_id = response.connection_id

        return {
            'connection_id': response.connection_id,
            'server_version': response.server_version
        }

    def disconnect(self) -> None:
        """Disconnect from database"""
        if not self.connection_id:
            return

        request = informix_pb2.DisconnectRequest(
            connection_id=self.connection_id
        )

        self.stub.Disconnect(request)
        self.connection_id = None
        self.channel.close()

    def ping(self) -> Dict[str, Any]:
        """
        Test connection

        Returns:
            Dict with 'alive' (bool) and 'latency_ms' (int)
        """
        self._check_connection()

        request = informix_pb2.PingRequest(
            connection_id=self.connection_id
        )

        response = self.stub.Ping(request)

        return {
            'alive': response.alive,
            'latency_ms': response.latency_ms
        }

    def query(
            self,
            sql: str,
            params: Optional[List[Any]] = None,
            fetch_size: int = 100,
            max_rows: int = 0
    ) -> Dict[str, Any]:
        """
        Execute a query and get all results

        Args:
            sql: SQL query
            params: Query parameters
            fetch_size: Rows per fetch (for streaming)
            max_rows: Maximum rows to return (0 = unlimited)

        Returns:
            Dict with 'rows', 'columns', and 'row_count'
        """
        self._check_connection()

        request = informix_pb2.QueryRequest(
            connection_id=self.connection_id,
            sql=sql,
            parameters=self._convert_parameters(params or []),
            fetch_size=fetch_size,
            max_rows=max_rows
        )

        rows = []
        columns = None
        total_rows = 0

        for response in self.stub.ExecuteQuery(request):
            if response.error:
                raise Exception(f"Query failed: {response.error}")

            # Save column metadata from first response
            if response.columns:
                columns = [
                    {
                        'name': col.name,
                        'type': col.type,
                        'precision': col.precision,
                        'scale': col.scale,
                        'nullable': col.nullable
                    }
                    for col in response.columns
                ]

            # Convert rows to dictionaries
            for row in response.rows:
                obj = {}
                for i, value in enumerate(row.values):
                    col_name = columns[i]['name']
                    obj[col_name] = self._convert_value(value)
                rows.append(obj)

            total_rows = response.total_rows

        return {
            'rows': rows,
            'columns': columns,
            'row_count': total_rows
        }

    def query_stream(
            self,
            sql: str,
            params: Optional[List[Any]] = None,
            on_row: Optional[Callable[[Dict], None]] = None,
            fetch_size: int = 100,
            max_rows: int = 0
    ) -> Dict[str, Any]:
        """
        Execute a query with streaming results

        Useful for large result sets to avoid loading everything into memory

        Args:
            sql: SQL query
            params: Query parameters
            on_row: Callback function called for each row
            fetch_size: Rows per fetch
            max_rows: Maximum rows to return (0 = unlimited)

        Returns:
            Dict with 'columns' and 'row_count'
        """
        self._check_connection()

        request = informix_pb2.QueryRequest(
            connection_id=self.connection_id,
            sql=sql,
            parameters=self._convert_parameters(params or []),
            fetch_size=fetch_size,
            max_rows=max_rows
        )

        columns = None
        total_rows = 0

        for response in self.stub.ExecuteQuery(request):
            if response.error:
                raise Exception(f"Query failed: {response.error}")

            if response.columns:
                columns = [
                    {
                        'name': col.name,
                        'type': col.type,
                        'precision': col.precision,
                        'scale': col.scale,
                        'nullable': col.nullable
                    }
                    for col in response.columns
                ]

            for row in response.rows:
                obj = {}
                for i, value in enumerate(row.values):
                    col_name = columns[i]['name']
                    obj[col_name] = self._convert_value(value)

                if on_row:
                    on_row(obj)

            total_rows = response.total_rows

        return {
            'columns': columns,
            'row_count': total_rows
        }

    def execute(self, sql: str, params: Optional[List[Any]] = None) -> int:
        """
        Execute an UPDATE/INSERT/DELETE statement

        Args:
            sql: SQL statement
            params: Statement parameters

        Returns:
            Number of rows affected
        """
        self._check_connection()

        request = informix_pb2.UpdateRequest(
            connection_id=self.connection_id,
            sql=sql,
            parameters=self._convert_parameters(params or [])
        )

        response = self.stub.ExecuteUpdate(request)

        if response.error:
            raise Exception(f"Execute failed: {response.error}")

        return response.rows_affected

    def batch(self, sql_statements: List[str]) -> List[int]:
        """
        Execute multiple statements in a batch

        Args:
            sql_statements: List of SQL statements

        Returns:
            List of rows affected for each statement
        """
        self._check_connection()

        request = informix_pb2.BatchRequest(
            connection_id=self.connection_id,
            sql_statements=sql_statements
        )

        response = self.stub.ExecuteBatch(request)

        if response.error:
            raise Exception(f"Batch failed: {response.error}")

        return list(response.rows_affected)

    def begin_transaction(self, isolation_level: str = 'READ_COMMITTED') -> None:
        """
        Begin a transaction

        Args:
            isolation_level: Transaction isolation level
                           (READ_UNCOMMITTED, READ_COMMITTED,
                            REPEATABLE_READ, SERIALIZABLE)
        """
        self._check_connection()

        request = informix_pb2.TransactionRequest(
            connection_id=self.connection_id,
            isolation_level=isolation_level
        )

        response = self.stub.BeginTransaction(request)

        if not response.success:
            raise Exception(f"Begin transaction failed: {response.error}")

    def commit(self) -> None:
        """Commit current transaction"""
        self._check_connection()

        request = informix_pb2.CommitRequest(
            connection_id=self.connection_id
        )

        response = self.stub.Commit(request)

        if not response.success:
            raise Exception(f"Commit failed: {response.error}")

    def rollback(self) -> None:
        """Rollback current transaction"""
        self._check_connection()

        request = informix_pb2.RollbackRequest(
            connection_id=self.connection_id
        )

        response = self.stub.Rollback(request)

        if not response.success:
            raise Exception(f"Rollback failed: {response.error}")

    def get_metadata(self, table_name: str = '') -> List[Dict[str, Any]]:
        """
        Get database metadata

        Args:
            table_name: Optional table name to get columns for

        Returns:
            List of table metadata dictionaries
        """
        self._check_connection()

        request = informix_pb2.MetadataRequest(
            connection_id=self.connection_id,
            table_name=table_name
        )

        response = self.stub.GetMetadata(request)

        if response.error:
            raise Exception(f"Get metadata failed: {response.error}")

        tables = []
        for table in response.tables:
            tables.append({
                'name': table.name,
                'schema': table.schema,
                'type': table.type,
                'columns': [
                    {
                        'name': col.name,
                        'type': col.type,
                        'precision': col.precision,
                        'scale': col.scale,
                        'nullable': col.nullable
                    }
                    for col in table.columns
                ]
            })

        return tables

    def __enter__(self):
        """Context manager entry"""
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit"""
        self.disconnect()

    # Helper methods

    def _check_connection(self):
        """Check if connected"""
        if not self.connection_id:
            raise Exception('Not connected to database. Call connect() first.')

    def _convert_parameters(self, params: List[Any]) -> List[informix_pb2.Parameter]:
        """Convert Python values to protobuf parameters"""
        result = []

        for param in params:
            if param is None:
                result.append(informix_pb2.Parameter(is_null=True))
            elif isinstance(param, str):
                result.append(informix_pb2.Parameter(string_value=param))
            elif isinstance(param, bool):
                result.append(informix_pb2.Parameter(bool_value=param))
            elif isinstance(param, int):
                result.append(informix_pb2.Parameter(int_value=param))
            elif isinstance(param, float):
                result.append(informix_pb2.Parameter(double_value=param))
            elif isinstance(param, bytes):
                result.append(informix_pb2.Parameter(bytes_value=param))
            else:
                result.append(informix_pb2.Parameter(string_value=str(param)))

        return result

    def _convert_value(self, value: informix_pb2.Value) -> Any:
        """Convert protobuf value to Python value"""
        if value.is_null:
            return None

        which = value.WhichOneof('data')

        if which == 'string_data':
            return value.string_data
        elif which == 'int_data':
            return value.int_data
        elif which == 'long_data':
            return value.long_data
        elif which == 'double_data':
            return value.double_data
        elif which == 'bool_data':
            return value.bool_data
        elif which == 'bytes_data':
            return bytes(value.bytes_data)

        return None


# Example usage
def example():
    """Example usage of InformixClient"""

    # Using context manager
    with InformixClient('localhost', 50051) as client:
        # Connect
        conn_info = client.connect(
            host='informix-server',
            port=9088,
            database='stores_demo',
            username='informix',
            password='informix',
            pool_size=10
        )
        print(f"Connected: {conn_info['server_version']}")

        # Simple query
        result = client.query(
            'SELECT * FROM customer WHERE customer_num < ?',
            params=[105]
        )
        print(f"Found {result['row_count']} customers")
        for row in result['rows']:
            print(f"  - {row['fname']} {row['lname']}")

        # Streaming large results
        print("\nStreaming all customers:")
        def print_customer(row):
            print(f"  - {row['fname']} {row['lname']}")

        client.query_stream(
            'SELECT * FROM customer',
            on_row=print_customer,
            fetch_size=50
        )

        # Transaction example
        client.begin_transaction()
        try:
            client.execute(
                'INSERT INTO customer (customer_num, fname, lname) VALUES (?, ?, ?)',
                params=[999, 'John', 'Doe']
            )
            client.execute(
                'UPDATE customer SET lname = ? WHERE customer_num = ?',
                params=['Smith', 999]
            )
            client.commit()
            print("\nTransaction committed")
        except Exception as e:
            client.rollback()
            print(f"Transaction rolled back: {e}")

        # Batch operations
        batch_results = client.batch([
            "UPDATE customer SET company = 'ACME' WHERE customer_num = 101",
            "UPDATE customer SET company = 'ACME' WHERE customer_num = 102"
        ])
        print(f"\nBatch results: {batch_results}")

        # Get metadata
        metadata = client.get_metadata('customer')
        if metadata:
            columns = [col['name'] for col in metadata[0]['columns']]
            print(f"\nCustomer table columns: {columns}")


if __name__ == '__main__':
    example()